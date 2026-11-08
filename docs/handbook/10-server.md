# 10 Rent a server

[Back to the handbook](README.md)

FoodStack runs on one Linux server to start with. This guide gets you a server and a way to type commands on it.

## What you need

- An account with a cloud provider that rents servers by the month (for example Hetzner, DigitalOcean, Vultr, AWS Lightsail, or a local provider in your country). A server close to your customers is faster for them.
- A credit card for the provider.
- About 20 minutes.

## Which server to choose

| | For a trial | For a real launch (one city) |
|---|---|---|
| Processors (vCPU) | 4 | 8 |
| Memory (RAM) | 16 GB | 32 GB |
| Disk | 100 GB SSD | 250 GB SSD (NVMe if offered) |
| System | Ubuntu 24.04 LTS | Ubuntu 24.04 LTS |

Debian 12 and Red Hat 9 style systems work too, but this handbook uses Ubuntu's commands. Pick the 64-bit image (amd64, or arm64 if the provider offers cheaper ARM servers: both work).

## Steps

1. **Make an SSH key** on your own computer, so you can sign in to the server without a password. Open a terminal (on Windows: PowerShell; on a Mac: Terminal) and run:
   ```bash
   ssh-keygen -t ed25519 -C "<your email>"
   ```
   Press Enter three times to accept the defaults. This makes two files in the `.ssh` folder of your home folder: `id_ed25519` (secret, never share it) and `id_ed25519.pub` (public, you give this one to the provider).
2. **Show the public key** and copy it:
   ```bash
   cat ~/.ssh/id_ed25519.pub
   ```
3. **Create the server** in the provider's control panel: choose the size from the table, Ubuntu 24.04, the region nearest your customers, and paste your public key where it asks for an SSH key. Give it a name such as `foodstack-1`.
4. Note the server's **public IPv4 address** (four numbers with dots, such as `203.0.113.10`).
5. **Sign in** to the server from your terminal:
   ```bash
   ssh root@<server IP address>
   ```
   Type `yes` when it asks whether to trust the server. You now type commands on the server; the prompt shows its name.
6. **Make a user for yourself** (working as `root` all the time is risky):
   ```bash
   adduser <your name>
   usermod -aG sudo <your name>
   rsync --archive --chown=<your name>:<your name> ~/.ssh /home/<your name>
   ```
   Choose a password when asked; you need it for `sudo`. Sign out (`exit`) and sign in again as yourself: `ssh <your name>@<server IP address>`.
7. **Install updates and Docker**:
   ```bash
   sudo apt update && sudo apt -y upgrade
   sudo apt -y install unzip
   curl -fsSL https://get.docker.com | sudo sh
   sudo usermod -aG docker $USER
   ```
   Sign out and in again, so the last line takes effect.
8. **Turn on the firewall**, letting in only SSH and the web:
   ```bash
   sudo ufw allow OpenSSH
   sudo ufw allow 80/tcp
   sudo ufw allow 443/tcp
   sudo ufw --force enable
   ```
9. **Turn on automatic security updates**:
   ```bash
   sudo apt -y install unattended-upgrades
   sudo dpkg-reconfigure -f noninteractive unattended-upgrades
   ```

## Check it worked

```bash
docker run --rm hello-world
sudo ufw status
```

- The first prints "Hello from Docker!".
- The second shows `Status: active` with `OpenSSH`, `80/tcp` and `443/tcp` allowed.

## Undo

Delete the server in the provider's control panel. Everything on it is gone, and billing stops.

## If something goes wrong

| Problem | What to do |
|---|---|
| `ssh` says "Permission denied (publickey)" | The provider did not get your public key. Add it in the control panel (often under "SSH keys" or "Access"), or rebuild the server with the key |
| `docker run` says "permission denied ... docker.sock" | You did not sign out and in again after step 7 |
| You locked yourself out with the firewall | Use the provider's web console (in the control panel) to sign in, then `sudo ufw allow OpenSSH` |
