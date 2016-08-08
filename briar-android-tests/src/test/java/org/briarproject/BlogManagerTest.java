package org.briarproject;

import net.jodah.concurrentunit.Waiter;

import org.briarproject.api.blogs.Blog;
import org.briarproject.api.blogs.BlogFactory;
import org.briarproject.api.blogs.BlogManager;
import org.briarproject.api.blogs.BlogPost;
import org.briarproject.api.blogs.BlogPostFactory;
import org.briarproject.api.blogs.BlogPostHeader;
import org.briarproject.api.contact.ContactId;
import org.briarproject.api.contact.ContactManager;
import org.briarproject.api.crypto.CryptoComponent;
import org.briarproject.api.crypto.KeyPair;
import org.briarproject.api.crypto.SecretKey;
import org.briarproject.api.db.DbException;
import org.briarproject.api.event.Event;
import org.briarproject.api.event.EventListener;
import org.briarproject.api.event.MessageStateChangedEvent;
import org.briarproject.api.identity.AuthorFactory;
import org.briarproject.api.identity.IdentityManager;
import org.briarproject.api.identity.LocalAuthor;
import org.briarproject.api.lifecycle.LifecycleManager;
import org.briarproject.api.sync.SyncSession;
import org.briarproject.api.sync.SyncSessionFactory;
import org.briarproject.api.system.Clock;
import org.briarproject.blogs.BlogsModule;
import org.briarproject.contact.ContactModule;
import org.briarproject.crypto.CryptoModule;
import org.briarproject.lifecycle.LifecycleModule;
import org.briarproject.properties.PropertiesModule;
import org.briarproject.sync.SyncModule;
import org.briarproject.transport.TransportModule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import javax.inject.Inject;

import static junit.framework.Assert.assertFalse;
import static org.briarproject.TestPluginsModule.MAX_LATENCY;
import static org.briarproject.api.sync.ValidationManager.State.DELIVERED;
import static org.briarproject.api.sync.ValidationManager.State.INVALID;
import static org.briarproject.api.sync.ValidationManager.State.PENDING;
import static org.briarproject.api.sync.ValidationManager.State.VALID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BlogManagerTest {

	private LifecycleManager lifecycleManager0, lifecycleManager1;
	private SyncSessionFactory sync0, sync1;
	private BlogManager blogManager0, blogManager1;
	private ContactManager contactManager0, contactManager1;
	private ContactId contactId0,contactId1;
	private IdentityManager identityManager0, identityManager1;
	private LocalAuthor author0, author1;
	private Blog blog0, blog1;

	@Inject
	Clock clock;
	@Inject
	AuthorFactory authorFactory;
	@Inject
	CryptoComponent crypto;
	@Inject
	BlogFactory blogFactory;
	@Inject
	BlogPostFactory blogPostFactory;

	// objects accessed from background threads need to be volatile
	private volatile Waiter validationWaiter;
	private volatile Waiter deliveryWaiter;

	private final File testDir = TestUtils.getTestDirectory();
	private final SecretKey master = TestUtils.getSecretKey();
	private final int TIMEOUT = 15000;
	private final String AUTHOR1 = "Author 1";
	private final String AUTHOR2 = "Author 2";
	private final String CONTENT_TYPE = "text/plain";

	private static final Logger LOG =
			Logger.getLogger(ForumSharingIntegrationTest.class.getName());

	private BlogManagerTestComponent t0, t1;

	@Rule
	public ExpectedException thrown = ExpectedException.none();

	@Before
	public void setUp() throws Exception {
		BlogManagerTestComponent component =
				DaggerBlogManagerTestComponent.builder().build();
		component.inject(this);
		injectEagerSingletons(component);

		assertTrue(testDir.mkdirs());
		File t0Dir = new File(testDir, AUTHOR1);
		t0 = DaggerBlogManagerTestComponent.builder()
				.testDatabaseModule(new TestDatabaseModule(t0Dir)).build();
		injectEagerSingletons(t0);
		File t1Dir = new File(testDir, AUTHOR2);
		t1 = DaggerBlogManagerTestComponent.builder()
				.testDatabaseModule(new TestDatabaseModule(t1Dir)).build();
		injectEagerSingletons(t1);

		identityManager0 = t0.getIdentityManager();
		identityManager1 = t1.getIdentityManager();
		contactManager0 = t0.getContactManager();
		contactManager1 = t1.getContactManager();
		blogManager0 = t0.getBlogManager();
		blogManager1 = t1.getBlogManager();
		sync0 = t0.getSyncSessionFactory();
		sync1 = t1.getSyncSessionFactory();

		// initialize waiters fresh for each test
		validationWaiter = new Waiter();
		deliveryWaiter = new Waiter();
	}

	@Test
	public void testPersonalBlogInitialisation() throws Exception {
		startLifecycles();

		defaultInit();

		Collection<Blog> blogs0 = blogManager0.getBlogs();
		assertEquals(2, blogs0.size());
		Iterator<Blog> i0 = blogs0.iterator();
		assertEquals(author0, i0.next().getAuthor());
		assertEquals(author1, i0.next().getAuthor());

		Collection<Blog> blogs1 = blogManager1.getBlogs();
		assertEquals(2, blogs1.size());
		Iterator<Blog> i1 = blogs1.iterator();
		assertEquals(author1, i1.next().getAuthor());
		assertEquals(author0, i1.next().getAuthor());

		assertEquals(blog0, blogManager0.getPersonalBlog(author0));
		assertEquals(blog0, blogManager1.getPersonalBlog(author0));
		assertEquals(blog1, blogManager0.getPersonalBlog(author1));
		assertEquals(blog1, blogManager1.getPersonalBlog(author1));

		assertEquals(blog0, blogManager0.getBlog(blog0.getId()));
		assertEquals(blog0, blogManager1.getBlog(blog0.getId()));
		assertEquals(blog1, blogManager0.getBlog(blog1.getId()));
		assertEquals(blog1, blogManager1.getBlog(blog1.getId()));

		assertEquals(1, blogManager0.getBlogs(author0).size());
		assertEquals(1, blogManager1.getBlogs(author0).size());
		assertEquals(1, blogManager0.getBlogs(author1).size());
		assertEquals(1, blogManager1.getBlogs(author1).size());

		stopLifecycles();
	}

	@Test
	public void testBlogPost() throws Exception {
		startLifecycles();
		defaultInit();

		// check that blog0 has no posts
		final byte[] body = TestUtils.getRandomBytes(42);
		Collection<BlogPostHeader> headers0 =
				blogManager0.getPostHeaders(blog0.getId());
		assertEquals(0, headers0.size());

		// add a post to blog0
		BlogPost p = blogPostFactory
				.createBlogPost(blog0.getId(), null, clock.currentTimeMillis(),
						null, author0, CONTENT_TYPE, body);
		blogManager0.addLocalPost(p);

		// check that post is now in blog0
		headers0 = blogManager0.getPostHeaders(blog0.getId());
		assertEquals(1, headers0.size());

		// check that body is there
		assertArrayEquals(body,
				blogManager0.getPostBody(p.getMessage().getId()));

		// make sure that blog0 at author1 doesn't have the post yet
		Collection<BlogPostHeader> headers1 =
				blogManager1.getPostHeaders(blog0.getId());
		assertEquals(0, headers1.size());

		// sync the post over
		sync0To1();
		deliveryWaiter.await(TIMEOUT, 1);

		// make sure post arrived
		headers1 = blogManager1.getPostHeaders(blog0.getId());
		assertEquals(1, headers1.size());

		// check that body is there
		assertArrayEquals(body,
				blogManager1.getPostBody(p.getMessage().getId()));

		stopLifecycles();
	}

	@Test
	public void testBlogPostInWrongBlog() throws Exception {
		startLifecycles();
		defaultInit();

		// add a post to blog1
		final byte[] body = TestUtils.getRandomBytes(42);
		BlogPost p = blogPostFactory
				.createBlogPost(blog1.getId(), null, clock.currentTimeMillis(),
						null, author0, CONTENT_TYPE, body);
		blogManager0.addLocalPost(p);

		// check that post is now in blog1
		Collection<BlogPostHeader> headers0 =
				blogManager0.getPostHeaders(blog1.getId());
		assertEquals(1, headers0.size());

		// sync the post over
		sync0To1();
		validationWaiter.await(TIMEOUT, 1);

		// make sure post did not arrive, because of wrong signature
		Collection<BlogPostHeader> headers1 =
				blogManager1.getPostHeaders(blog1.getId());
		assertEquals(0, headers1.size());

		stopLifecycles();
	}

	@Test
	public void testAddAndRemoveBlog() throws Exception {
		startLifecycles();
		defaultInit();

		String name = "Test Blog";
		String desc = "Description";

		// add blog
		Blog blog = blogManager0.addBlog(author0, name, desc);
		Collection<Blog> blogs0 = blogManager0.getBlogs();
		assertEquals(3, blogs0.size());
		assertTrue(blogs0.contains(blog));
		assertEquals(2, blogManager0.getBlogs(author0).size());
		assertTrue(blogManager0.canBeRemoved(blog.getId()));

		// remove blog
		blogManager0.removeBlog(blog);
		blogs0 = blogManager0.getBlogs();
		assertEquals(2, blogs0.size());
		assertFalse(blogs0.contains(blog));
		assertEquals(1, blogManager0.getBlogs(author0).size());

		stopLifecycles();
	}

	@Test
	public void testCanNotRemoveContactsPersonalBlog() throws Exception {
		startLifecycles();
		defaultInit();

		assertFalse(blogManager0.canBeRemoved(blog1.getId()));
		assertFalse(blogManager1.canBeRemoved(blog0.getId()));

		// the following two calls should throw a DbException now
		thrown.expect(DbException.class);

		blogManager0.removeBlog(blog1);
		blogManager1.removeBlog(blog0);

		// blogs have not been removed
		assertEquals(2, blogManager0.getBlogs().size());
		assertEquals(2, blogManager1.getBlogs().size());

		stopLifecycles();
	}

	@After
	public void tearDown() throws Exception {
		TestUtils.deleteTestDirectory(testDir);
	}

	private class Listener implements EventListener {
		public void eventOccurred(Event e) {
			if (e instanceof MessageStateChangedEvent) {
				MessageStateChangedEvent event = (MessageStateChangedEvent) e;
				if (!event.isLocal()) {
					if (event.getState() == DELIVERED) {
						deliveryWaiter.resume();
					} else if (event.getState() == VALID ||
							event.getState() == INVALID ||
							event.getState() == PENDING) {
						validationWaiter.resume();
					}
				}
			}
		}
	}

	private void defaultInit() throws DbException {
		addDefaultIdentities();
		addDefaultContacts();
		listenToEvents();
	}

	private void addDefaultIdentities() throws DbException {
		KeyPair keyPair0 = crypto.generateSignatureKeyPair();
		byte[] publicKey0 = keyPair0.getPublic().getEncoded();
		byte[] privateKey0 = keyPair0.getPrivate().getEncoded();
		author0 = authorFactory
				.createLocalAuthor(AUTHOR1, publicKey0, privateKey0);
		identityManager0.addLocalAuthor(author0);
		blog0 = blogFactory.createPersonalBlog(author0);

		KeyPair keyPair1 = crypto.generateSignatureKeyPair();
		byte[] publicKey1 = keyPair1.getPublic().getEncoded();
		byte[] privateKey1 = keyPair1.getPrivate().getEncoded();
		author1 = authorFactory
				.createLocalAuthor(AUTHOR2, publicKey1, privateKey1);
		identityManager1.addLocalAuthor(author1);
		blog1 = blogFactory.createPersonalBlog(author1);
	}

	private void addDefaultContacts() throws DbException {
		// sharer adds invitee as contact
		contactId1 = contactManager0.addContact(author1,
				author0.getId(), master, clock.currentTimeMillis(), true,
				true
		);
		// invitee adds sharer back
		contactId0 = contactManager1.addContact(author0,
				author1.getId(), master, clock.currentTimeMillis(), true,
				true
		);
	}

	private void listenToEvents() {
		Listener listener0 = new Listener();
		t0.getEventBus().addListener(listener0);
		Listener listener1 = new Listener();
		t1.getEventBus().addListener(listener1);
	}

	private void sync0To1() throws IOException, TimeoutException {
		deliverMessage(sync0, contactId0, sync1, contactId1, "0 to 1");
	}

	private void sync1To0() throws IOException, TimeoutException {
		deliverMessage(sync1, contactId1, sync0, contactId0, "1 to 0");
	}

	private void deliverMessage(SyncSessionFactory fromSync, ContactId fromId,
			SyncSessionFactory toSync, ContactId toId, String debug)
			throws IOException, TimeoutException {

		if (debug != null) LOG.info("TEST: Sending message from " + debug);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		// Create an outgoing sync session
		SyncSession sessionFrom =
				fromSync.createSimplexOutgoingSession(toId, MAX_LATENCY, out);
		// Write whatever needs to be written
		sessionFrom.run();
		out.close();

		ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
		// Create an incoming sync session
		SyncSession sessionTo = toSync.createIncomingSession(fromId, in);
		// Read whatever needs to be read
		sessionTo.run();
		in.close();
	}

	private void startLifecycles() throws InterruptedException {
		// Start the lifecycle manager and wait for it to finish
		lifecycleManager0 = t0.getLifecycleManager();
		lifecycleManager1 = t1.getLifecycleManager();
		lifecycleManager0.startServices();
		lifecycleManager1.startServices();
		lifecycleManager0.waitForStartup();
		lifecycleManager1.waitForStartup();
	}

	private void stopLifecycles() throws InterruptedException {
		// Clean up
		lifecycleManager0.stopServices();
		lifecycleManager1.stopServices();
		lifecycleManager0.waitForShutdown();
		lifecycleManager1.waitForShutdown();
	}

	private void injectEagerSingletons(BlogManagerTestComponent component) {
		component.inject(new LifecycleModule.EagerSingletons());
		component.inject(new BlogsModule.EagerSingletons());
		component.inject(new CryptoModule.EagerSingletons());
		component.inject(new ContactModule.EagerSingletons());
		component.inject(new TransportModule.EagerSingletons());
		component.inject(new SyncModule.EagerSingletons());
		component.inject(new PropertiesModule.EagerSingletons());
	}

}