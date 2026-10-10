# Custom companion skins

Custom skins are optional. With no valid custom skin, every client uses the
companion UUID to make a stable vanilla choice: classic Steve or slim Alex.

## Import

1. Create a modern Java Edition skin PNG that is exactly `64 × 64`, contains
   an alpha channel, and is no larger than `1 MiB`.
2. Put it at the fixed instance-local path
   `config/mcai-companion/skin.png`.
3. Run one of these commands as the singleplayer owner or a server
   gamemaster:

   - `/mcai skin reload classic`
   - `/mcai skin reload slim`

On a world's first setup, the fixed file is also imported automatically when
`skin.autoImportFixedFile=true`; `skin.armType` controls its arm geometry.

Use `/mcai skin status` to report the active arm type and content digest.
Use `/mcai skin clear` to persistently restore the UUID-based default. A later
explicit `reload` enables a custom skin again.

## Security and synchronization

- The mod never accepts a skin URL or an arbitrary command/chat path.
- Only the fixed local file is read, and only by the server host.
- Validation checks the PNG signature before decoding, checks dimensions
  before a full image read, requires an alpha channel, and enforces the
  one-MiB input limit.
- The cache key is SHA-256. Cached content is revalidated before use.
- Clients receive bounded 32-KiB chunks containing only the companion UUID,
  digest, arm type, and PNG bytes. The host's file path is never sent.
- As with any multiplayer skin, the selected PNG itself must be distributed to
  connected clients so they can render it; do not use an image containing
  private information.
- Clients independently validate and hash the reconstructed PNG before
  creating a texture. Missing, damaged, interrupted, or rejected transfers
  immediately use Steve/Alex fallback.
- Disconnect releases dynamic GPU textures. The content-addressed disk cache
  may remain for efficient reconnects.

The renderer override changes only `PlayerSkin.body` and its wide/slim model.
It does not replace the player renderer or entity, so armor, held items,
shield, bow, eating, swinging, sneaking, sprinting, swimming, and sleeping
continue to use vanilla player rendering and animation.
