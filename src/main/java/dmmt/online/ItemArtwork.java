package dmmt.online;

/** Only local, bundled item icons may be referenced by catalog and chat metadata. */
public final class ItemArtwork {
    private ItemArtwork() { }

    public static boolean validPath(String image) {
        return image != null && image.matches("/item-icons/[a-z0-9-]+/[a-z0-9-]+\\.svg");
    }

    public static java.net.URL resource(String image) {
        return validPath(image) ? ItemArtwork.class.getResource("/web" + image) : null;
    }
}
