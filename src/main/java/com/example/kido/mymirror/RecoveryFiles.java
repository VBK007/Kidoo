package com.example.kido.mymirror;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

final class RecoveryFiles {
    private RecoveryFiles() {}

    static void requirePrivate(Path file, boolean directory) throws IOException {
        if (!file.toAbsolutePath().normalize().equals(file.toRealPath())) {
            throw new IOException("Recovery paths must not contain symbolic links");
        }
        var attributes = Files.readAttributes(file, java.nio.file.attribute.PosixFileAttributes.class, NOFOLLOW_LINKS);
        var owner = file.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        var mode = PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------");
        if (!(directory ? attributes.isDirectory() : attributes.isRegularFile())
                || !attributes.owner().equals(owner) || !attributes.permissions().equals(mode)) {
            throw new IOException("Unsafe recovery path ownership or mode");
        }
    }

    static String read(Path file) throws IOException {
        requirePrivate(file, false);
        try (var channel = FileChannel.open(file, READ, NOFOLLOW_LINKS)) {
            var bytes = ByteBuffer.allocate(8193);
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) { }
            if (!bytes.hasRemaining()) throw new IOException("Recovery file exceeds size limit");
            return StandardCharsets.UTF_8.decode(bytes.flip()).toString();
        }
    }

    static void replace(Path target, String content) throws IOException {
        requirePrivate(target.getParent(), true);
        if (Files.exists(target, NOFOLLOW_LINKS)) requirePrivate(target, false);
        var temporary = Files.createTempFile(target.getParent(), ".recovery-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var channel = FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS)) {
                var bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(target.getParent(), READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
