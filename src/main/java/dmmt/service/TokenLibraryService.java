package dmmt.service;

import dmmt.model.DmProject;
import dmmt.model.DmProject.TokenDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Library identity and artwork are separate from independently saved map instances.
 */
public final class TokenLibraryService
{
    @Data
    public static class Entry
    {
        private TokenDefinition token;
        private String folder = "";
        private boolean deleted;
    }

    @Data
    public static class Library
    {
        private List<String> folders = new ArrayList<>();
        private List<Entry> entries = new ArrayList<>();
    }

    private final Path file;
    private final ObjectMapper mapper = JsonMappers.create();

    public TokenLibraryService(Path root)
    {
        file = root.resolve("tokens.json");
    }

    public static TokenLibraryService configured()
    {
        return new TokenLibraryService(AppSettings.resolveFile().toAbsolutePath().getParent().resolve("dmmap-tokens"));
    }

    public synchronized Library load() throws IOException
    {
        Library library = Files.exists(file) ? mapper.readValue(file.toFile(), Library.class) : new Library();
        for (Entry entry : library.entries)
            TokenAssets.read(entry.token, file.getParent());
        return library;
    }

    private void save(Library library) throws IOException
    {
        library.entries.removeIf(Entry::isDeleted);
        Files.createDirectories(file.getParent());
        Path staging = Files.createTempFile(file.getParent(), "tokens-", ".tmp");
        try
        {
            Library stored = mapper.convertValue(library, Library.class);
            for (Entry entry : stored.entries)
                TokenAssets.write(entry.token, file.getParent(), "images");
            mapper.writerWithDefaultPrettyPrinter().writeValue(staging.toFile(), stored);
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
            var referenced = new java.util.HashSet<Path>();
            for (Entry entry : stored.entries)
            {
                if (entry.token.getSourceImage() != null)
                    referenced.add(file.getParent().resolve(entry.token.getSourceImage()).toAbsolutePath().normalize());
                if (entry.token.getImage() != null)
                    referenced.add(file.getParent().resolve(entry.token.getImage()).toAbsolutePath().normalize());
            }
            Path images = file.getParent().resolve("images");
            if (Files.isDirectory(images))
            {
                try (var files = Files.list(images))
                {
                    for (Path image : files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}\\.png")).toList())
                    {
                        if (!referenced.contains(image.toAbsolutePath().normalize()))
                            Files.delete(image);
                    }
                }
            }
        }
        finally
        {
            Files.deleteIfExists(staging);
        }
    }

    public synchronized void put(TokenDefinition token, String folder) throws IOException
    {
        validate(token);
        Library library = load();
        requireFolder(library, folder);
        Entry entry = library.entries.stream().filter(e -> e.token.getId().equals(token.getId())).findFirst().orElse(null);
        if (entry == null)
        {
            entry = new Entry();
            library.entries.add(entry);
        }
        entry.setToken(copy(token));
        entry.setFolder(folder);
        entry.setDeleted(false);
        save(library);
    }

    public synchronized void delete(String id) throws IOException
    {
        Library library = load();
        Entry entry = find(library, id);
        library.entries.remove(entry);
        save(library);
    }

    public synchronized void move(String id, String folder) throws IOException
    {
        Library library = load();
        requireFolder(library, folder);
        find(library, id).setFolder(folder);
        save(library);
    }

    public synchronized String createFolder(String parent, String name) throws IOException
    {
        Library library = load();
        requireFolder(library, parent);
        String folder = parent.isEmpty() ? validName(name) : parent + "/" + validName(name);
        if (library.folders.stream().anyMatch(f -> f.equalsIgnoreCase(folder)))
        {
            throw new IOException("A folder with that name already exists.");
        }
        library.folders.add(folder);
        save(library);
        return folder;
    }

    public synchronized void moveFolder(String folder, String parent, String name) throws IOException
    {
        Library library = load();
        requireFolder(library, folder);
        requireFolder(library, parent);
        if (folder.isEmpty() || within(parent, folder))
        {
            throw new IOException("Cannot move a folder into itself or a subfolder.");
        }
        String target = parent.isEmpty() ? validName(name) : parent + "/" + validName(name);
        if (target.equals(folder))
        {
            return;
        }
        if (library.folders.stream().anyMatch(f -> f.equalsIgnoreCase(target) && !f.equals(folder)))
        {
            throw new IOException("A folder with that name already exists.");
        }
        library.folders.replaceAll(f -> within(f, folder) ? target + f.substring(folder.length()) : f);
        library.entries.forEach(e -> {
            if (within(e.folder, folder))
                e.folder = target + e.folder.substring(folder.length());
        });
        save(library);
    }

    public synchronized void deleteFolder(String folder) throws IOException
    {
        if (folder.isEmpty())
            throw new IOException("Cannot delete the library root.");
        Library library = load();
        requireFolder(library, folder);
        library.folders.removeIf(f -> within(f, folder));
        library.entries.removeIf(e -> within(e.folder, folder));
        save(library);
    }

    public void resolve(DmProject project) throws IOException
    {
        if (project.getTokens().isEmpty())
            return;
        Library library = load();
        for (Entry entry : library.entries)
        {
            if (project.getTokenDefinitions().containsKey(entry.token.getId()))
            {
                project.getTokenDefinitions().put(entry.token.getId(), copy(entry.token));
            }

        }
    }

    public byte[] embedLatest(Path mapFile) throws IOException
    {
        var json = mapper.readTree(mapFile.toFile());
        if (json instanceof com.fasterxml.jackson.databind.node.ObjectNode object
                && object.path("tokenDefinitions").isObject() && !object.path("tokenDefinitions").isEmpty())
        {
            var definitions = (com.fasterxml.jackson.databind.node.ObjectNode)object.get("tokenDefinitions");
            var fields = definitions.fields();
            while (fields.hasNext())
            {
                var field = fields.next();
                TokenDefinition token = mapper.treeToValue(field.getValue(), TokenDefinition.class);
                TokenAssets.read(token, mapFile.toAbsolutePath().getParent());
                definitions.set(field.getKey(), mapper.valueToTree(token));
            }
            for (Entry entry : load().entries)
            {
                if (definitions.has(entry.token.getId()))
                {
                    definitions.set(entry.token.getId(), mapper.valueToTree(entry.token));
                }
            }
            var storedFields = definitions.fields();
            while (storedFields.hasNext())
            {
                var field = storedFields.next();
                TokenDefinition token = mapper.treeToValue(field.getValue(), TokenDefinition.class);
                TokenAssets.write(token, mapFile.toAbsolutePath().getParent(), "assets\\tokens");
                definitions.set(field.getKey(), mapper.valueToTree(token));
            }
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(object);
        }
        return Files.readAllBytes(mapFile);
    }

    public static TokenDefinition copy(TokenDefinition token)
    {
        return TokenDefinition.builder().id(token.getId()).name(token.getName()).color(token.getColor())
                              .sizeTiles(token.getSizeTiles()).sourceImage(token.getSourceImage()).image(token.getImage())
                              .zoom(token.getZoom()).centerX(token.getCenterX()).centerY(token.getCenterY()).build();
    }

    public static TokenDefinition duplicate(TokenDefinition token)
    {
        TokenDefinition copy = copy(token);
        copy.setId(UUID.randomUUID().toString());
        copy.setName(token.getName() + " (Copy)");
        return copy;
    }

    public static void validate(TokenDefinition token) throws IOException
    {
        if (token.getId() == null || token.getId().isBlank())
            throw new IOException("Token identity is missing.");
        if (token.getName() == null || token.getName().isBlank() || token.getName().matches(".*\\p{Cntrl}.*"))
        {
            throw new IOException("Enter a non-empty token name without control characters.");
        }
        token.setName(token.getName().trim());
        if (!Double.isFinite(token.getSizeTiles()) || token.getSizeTiles() < 0.5
                || token.getSizeTiles() * 2 != Math.rint(token.getSizeTiles() * 2))
        {
            throw new IOException("Token size must be a positive multiple of 0.5 tiles.");
        }
        if (token.getColor() == null || !token.getColor().matches("#[0-9A-Fa-f]{6}"))
        {
            throw new IOException("Token color must be #RRGGBB.");
        }
    }

    private static String validName(String name) throws IOException
    {
        if (name == null || name.isBlank() || name.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*")
                || name.trim().equals(".") || name.trim().equals(".."))
        {
            throw new IOException("Enter a non-empty name without path separators or special filename characters.");
        }
        return name.trim();
    }

    private static void requireFolder(Library library, String folder) throws IOException
    {
        if (folder == null || (!folder.isEmpty() && !library.folders.contains(folder)))
        {
            throw new IOException("The token folder no longer exists.");
        }
    }

    private static Entry find(Library library, String id) throws IOException
    {
        return library.entries.stream().filter(e -> !e.deleted && e.token.getId().equals(id)).findFirst()
                              .orElseThrow(() -> new IOException("The token no longer exists in the library."));
    }

    public static boolean within(String folder, String parent)
    {
        return folder.equals(parent) || folder.startsWith(parent + "/");
    }

    public static boolean matches(String name, String folder, String query)
    {
        String text = (name + " " + folder.replace('/', ' ')).toLowerCase(Locale.ROOT);
        return java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).trim().split("\\s+")).allMatch(text::contains);
    }
}
