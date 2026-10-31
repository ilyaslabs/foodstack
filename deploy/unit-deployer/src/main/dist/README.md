# FoodStack unit deployer

Everything to run FoodStack on one Linux server with Docker: the server jar, the built web apps,
Docker Compose and the scripts below. Full instructions: `docs/deployment-guide.md` section 3.

```bash
sudo ./install.sh /opt/foodstack --domain example.com --admin-domain admin.example.com \
  --api-domain api.example.com --admin-email ops@example.com
```

For a quick local trial without certificates: `sudo ./install.sh /opt/foodstack --domain localhost --http --http-port 8088`.

| Script | Does |
|---|---|
| `install.sh <dir> --domain …` | Checks the server, creates the directories, generates secrets into `<dir>/.env`, copies the files, runs migrations once, starts the stack, prints the first sign-in |
| `start.sh` / `stop.sh [service…]` | Start everything / stop everything or the named services (data is kept) |
| `restart.sh [service]` | Restart everything or one service |
| `status.sh` | Health of every service and disk usage |
| `logs.sh [service]` | Follow logs |
| `backup.sh` | MongoDB dump, media and configuration into `backups/<timestamp>` |
| `restore.sh <backup-dir>` | Restore a backup |
| `upgrade.sh <zip>` / `--rollback` | Back up, replace the application, migrate, restart / go back to the previous files |

Services: `proxy` (Caddy), `server` (Temurin 25 JRE with the mounted jar), `web` (Node, SSR),
`backoffice` (nginx-unprivileged), `mongodb` (single-node replica set), `kafka` (KRaft),
`redis`, `storage` (RustFS, S3-compatible), and `elasticsearch` (Compose profile `search`, used
from M1).
