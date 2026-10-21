package logflow;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileLineSource implements Source<String> {
    private final Path path;

    public FileLineSource(Path path) {
        this.path = path;
    }

    @Override
    public void produce(Emitter<String> out) {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.emit(line);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read file: " + path, e);
        }
    }
}
