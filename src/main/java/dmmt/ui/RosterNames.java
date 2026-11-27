package dmmt.ui;

import dmmt.online.OnlineSessionServer;

final class RosterNames {
    private RosterNames() {
    }

    static String displayName(OnlineSessionServer.RosterEntry player) {
        String identity = player.chatIdentity();
        if (identity == null || identity.isEmpty()) return player.playerName();
        String pronouns = player.pronouns();
        return player.playerName() + " (" + identity + ")"
                + (pronouns == null || pronouns.isBlank() ? "" : "[" + pronouns + "]");
    }
}
