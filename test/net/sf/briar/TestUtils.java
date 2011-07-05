package net.sf.briar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import net.sf.briar.api.protocol.UniqueId;

public class TestUtils {

	private static final AtomicInteger nextTestDir =
		new AtomicInteger((int) (Math.random() * 1000 * 1000));
	private static final Random random = new Random();

	public static void delete(File f) {
		if(f.isDirectory()) for(File child : f.listFiles()) delete(child);
		f.delete();
	}

	public static void createFile(File f, String s) throws IOException {
		f.getParentFile().mkdirs();
		PrintStream out = new PrintStream(new FileOutputStream(f));
		out.print(s);
		out.flush();
		out.close();
	}

	public static File getTestDirectory() {
		int name = nextTestDir.getAndIncrement();
		File testDir = new File("test.tmp/" + name);
		return testDir;
	}

	public static void deleteTestDirectory(File testDir) {
		delete(testDir);
		testDir.getParentFile().delete(); // Delete if empty
	}

	public static File getBuildDirectory() {
		File build = new File("build"); // Ant
		if(build.exists() && build.isDirectory()) return build;
		File bin = new File("bin"); // Eclipse
		if(bin.exists() && bin.isDirectory()) return bin;
		throw new RuntimeException("Could not find build directory");
	}

	public static byte[] getRandomId() {
		byte[] b = new byte[UniqueId.LENGTH];
		random.nextBytes(b);
		return b;
	}
}
