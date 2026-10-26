import java.nio.file.*;
import java.util.Map;
import java.util.zip.ZipFile;

class CheckBuildZip {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]).toAbsolutePath();
        for (Path part = path; part != null; part = part.getParent()) {
            try { System.out.println("real=" + part.toRealPath()); }
            catch (Exception error) { System.out.println("real failed=" + part + ": " + error.getClass().getSimpleName()); }
        }
        System.out.println("exists=" + Files.exists(path) + ", readable=" + Files.isReadable(path) + ", writable=" + Files.isWritable(path));
        try (ZipFile zip = new ZipFile(path.toFile())) {
            System.out.println("ZipFile entries=" + zip.size());
        }
        try (FileSystem zip = FileSystems.newFileSystem(path, Map.of())) {
            System.out.println("ZipFS opened");
        }
    }
}
