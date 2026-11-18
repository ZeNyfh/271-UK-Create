# Server JVM arg profiles (zenvibelaptop, 32G)

| Profile | Path | Use |
|---------|------|-----|
| **play** (default) | `user_jvm_args.txt` / `play/user_jvm_args.txt` | Players online; deploy syncs this |
| **pregen** | `pregen/user_jvm_args.txt` | Chunky / mass chunk gen |

Deploy (`scripts/deploy-uk-create.sh`) always syncs `config/server/user_jvm_args.txt` (play). It does **not** auto-switch to pregen.

## Switch on the live server

Active file is `user_jvm_args.txt` next to `run.sh`. Profiles are also mirrored under `jvm-args/`:

```bash
cd ~/uk-create-server
cp jvm-args/pregen/user_jvm_args.txt user_jvm_args.txt   # then restart
cp jvm-args/play/user_jvm_args.txt user_jvm_args.txt     # then restart
```

Or from this repo (when the sshfs mount is up):

```bash
cp config/server/pregen/user_jvm_args.txt ~/laptop_home/uk-create-server/user_jvm_args.txt
cp config/server/play/user_jvm_args.txt   ~/laptop_home/uk-create-server/user_jvm_args.txt
```

Restart the Minecraft process after switching. Deploy will overwrite live args with **play** on the next `start_server` / JVM sync.

## server.properties overrides

Do **not** blindly overwrite remote `server.properties`. Merge by hand from:

- `pregen/server.properties.overrides.txt` — `max-tick-time=-1`, VD 6 / SD 4
- `play/server.properties.overrides.txt` — `max-tick-time=300000`, VD 10 / SD 8
