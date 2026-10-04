import java.io.*; import java.util.zip.*; import org.objectweb.asm.*;
public class ListDonors { public static void main(String[] a) throws Exception {
  try (ZipInputStream z = new ZipInputStream(new FileInputStream(a[0]))) { ZipEntry e; while ((e = z.getNextEntry()) != null) {
    if (!e.getName().endsWith(".class")) continue; ClassReader r = new ClassReader(z.readAllBytes()); System.out.println(((r.getAccess() & Opcodes.ACC_INTERFACE) != 0 ? "I " : "C ") + r.getClassName()); } } } }
