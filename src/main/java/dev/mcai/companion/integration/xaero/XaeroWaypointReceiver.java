package dev.mcai.companion.integration.xaero;

import java.util.Objects;
import java.util.UUID;

/**
 * Authorization boundary for structured waypoint chat.
 *
 * <p>The output is data-only. This receiver does not forward chat or labels to
 * a model prompt and has no command, teleport, OCR, or navigation side
 * effects.</p>
 */
public final class XaeroWaypointReceiver {
    private final XaeroSenderAuthorizationPolicy authorization;

    public XaeroWaypointReceiver(XaeroSenderAuthorizationPolicy authorization) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    public XaeroReceiveResult receive(UUID senderId, String chat) {
        Objects.requireNonNull(senderId, "senderId");
        if (!authorization.isAuthorized(senderId)) {
            return XaeroReceiveResult.rejected(XaeroReceiveStatus.UNAUTHORIZED_SENDER);
        }

        try {
            return XaeroReceiveResult.accepted(XaeroWaypointParser.parse(chat));
        } catch (XaeroWaypointParseException exception) {
            XaeroReceiveStatus status =
                    exception.reason() == XaeroWaypointParseException.Reason.NOT_XAERO_MESSAGE
                            ? XaeroReceiveStatus.NOT_XAERO_WAYPOINT
                            : XaeroReceiveStatus.MALFORMED_WAYPOINT;
            return XaeroReceiveResult.rejected(status);
        }
    }
}
