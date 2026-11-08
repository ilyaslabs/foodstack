# 12 Storage for photos and files

[Back to the handbook](README.md)

FoodStack keeps every photo and file (restaurant logos, dishes, review photos, riders' papers) at a storage provider, never on your server. You create two **buckets** there, which are folders in the provider's cloud:

- a **public** bucket, for images anyone may see, such as dishes and logos;
- a **private** bucket, for files only signed-in staff may open, such as riders' papers.

Browsers upload straight to the buckets and load images from them, so your server's disk only holds the database. The same buckets stay with you when you grow to Kubernetes later.

Do this guide before [installing](14-install.md): the installer asks for what you note here, and FoodStack checks the buckets every time it starts.

## What you need

- An account at one storage provider. This guide covers three; any S3-compatible provider works the same way.

  | Provider | Good to know |
  |---|---|
  | **Amazon S3** | The best known. You pay for storage and for every download |
  | **Cloudflare R2** | No download charges. Images are served from a name on your own domain, which must use Cloudflare's DNS |
  | **Backblaze B2** | Low storage prices |

- Your customer and back office addresses, for example `https://example.com` and `https://admin.example.com` ([guide 11](11-domain-and-dns.md)).
- About 30 minutes.

Write down these seven values as you go, in your password manager, in a note named "FoodStack storage":

| Name | What it is |
|---|---|
| `S3_ENDPOINT` | The provider's S3 address |
| `S3_REGION` | The buckets' region |
| `S3_PUBLIC_BUCKET` | The public bucket's name |
| `S3_PRIVATE_BUCKET` | The private bucket's name |
| `S3_PUBLIC_BASE_URL` | Where the public bucket's files are served |
| `S3_ACCESS_KEY` | The key's id |
| `S3_SECRET_KEY` | The key's secret |

Bucket names are shared by everyone on Amazon S3 and Backblaze B2, so pick names with your business in them, such as `tasty-foodstack-public` and `tasty-foodstack-private`.

## Steps

Follow the part for your provider.

### Amazon S3

1. Sign in to the [AWS console](https://console.aws.amazon.com/s3/) and choose a region near your customers (top right, for example `eu-west-1` or `me-central-1`). Note it as `S3_REGION`.
2. **Create the public bucket**: **Create bucket**, enter its name, and under **Block Public Access settings for this bucket** untick **Block all public access**, then tick the warning that appears. Keep everything else as it is and click **Create bucket**.
3. **Let anyone read it**: open the bucket, **Permissions**, **Bucket policy**, **Edit**, and paste, with your bucket's name:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Sid": "PublicRead", "Effect": "Allow", "Principal": "*",
       "Action": "s3:GetObject", "Resource": "arn:aws:s3:::<public bucket>/*"
     }]
   }
   ```
4. **Create the private bucket** the same way, but **keep** "Block all public access" ticked, and add no bucket policy.
5. **Allow uploads from your sites**, on **both** buckets: **Permissions**, **Cross-origin resource sharing (CORS)**, **Edit**, and paste, with your two addresses:
   ```json
   [{
     "AllowedOrigins": ["https://example.com", "https://admin.example.com"],
     "AllowedMethods": ["PUT", "GET"],
     "AllowedHeaders": ["*"],
     "ExposeHeaders": ["ETag"],
     "MaxAgeSeconds": 3600
   }]
   ```
6. **Make a key for FoodStack**: open **IAM**, **Users**, **Create user**, name it `foodstack`, and on the permissions page choose **Attach policies directly**, then **Create policy**, the **JSON** tab, and paste, with both bucket names:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket"],
       "Resource": ["arn:aws:s3:::<public bucket>", "arn:aws:s3:::<public bucket>/*",
                    "arn:aws:s3:::<private bucket>", "arn:aws:s3:::<private bucket>/*"]
     }]
   }
   ```
   Name the policy `foodstack-buckets`, attach it to the user, and finish. Then open the user, **Security credentials**, **Create access key**, choose **Application running outside AWS**, and note the **Access key** and **Secret access key**. The secret is shown once.
7. Your values:
   ```
   S3_ENDPOINT=https://s3.<region>.amazonaws.com
   S3_REGION=<region>
   S3_PUBLIC_BASE_URL=https://<public bucket>.s3.<region>.amazonaws.com
   ```

### Cloudflare R2

1. In the [Cloudflare dashboard](https://dash.cloudflare.com), open **R2 Object Storage** and **Create bucket** twice: the public one and the private one (location: automatic).
2. **Give the public bucket an address**: open it, **Settings**, **Public access**, **Custom Domains**, **Connect domain**, and enter a name on your domain such as `media.example.com`. Cloudflare adds the DNS record itself. Note `https://media.example.com` as `S3_PUBLIC_BASE_URL`. (The `r2.dev` address Cloudflare also offers is slowed down on purpose and is for testing only.)
3. Leave the private bucket's public access off.
4. **Allow uploads from your sites**, on **both** buckets: **Settings**, **CORS Policy**, **Add CORS policy**, and paste the same JSON as in Amazon S3, step 5.
5. **Make a key for FoodStack**: back on the R2 page, **Manage API tokens** (or **API**), **Create API token**. Choose **Object Read & Write**, limit it to your two buckets, and create it. Note the **Access Key ID**, the **Secret Access Key** and the S3 address shown under it (`https://<account id>.r2.cloudflarestorage.com`). The secret is shown once.
6. Your values:
   ```
   S3_ENDPOINT=https://<account id>.r2.cloudflarestorage.com
   S3_REGION=auto
   S3_PUBLIC_BASE_URL=https://media.example.com
   ```

### Backblaze B2

1. Sign in to [Backblaze](https://secure.backblaze.com/b2_buckets.htm), **Buckets**, **Create a Bucket**: the public one with **Files in Bucket are: Public**, then the private one with **Private**.
2. Each bucket's card shows its **Endpoint**, such as `s3.us-west-004.backblazeb2.com`. The part after `s3.` and before `.backblazeb2.com` is the region (`us-west-004`).
3. **Allow uploads from your sites**, on **both** buckets: **CORS Rules**, choose **Share everything in this bucket with all HTTPS origins**, and under **Apply CORS rules to the following** choose **S3 Compatible API**. Save. (Uploads still need a link FoodStack signs, so this does not let anyone else upload.)
4. **Make a key for FoodStack**: **Application Keys**, **Add a New Application Key**, name it `foodstack`, allow access to **All** buckets (one key must reach both), choose **Read and Write**, and create it. Note the **keyID** and the **applicationKey**. The key is shown once.
5. Your values:
   ```
   S3_ENDPOINT=https://s3.<region>.backblazeb2.com
   S3_REGION=<region>
   S3_PUBLIC_BASE_URL=https://s3.<region>.backblazeb2.com/<public bucket>
   ```

### Any provider: put the values in your settings file

8. On your server, in your home folder, start the settings file the installer reads ([guide 14](14-install.md) adds the map lines from [guide 13](13-maps.md)):
   ```bash
   nano ~/foodstack-settings.env
   ```
   ```
   S3_ENDPOINT=<value>
   S3_REGION=<value>
   S3_PUBLIC_BUCKET=<value>
   S3_PRIVATE_BUCKET=<value>
   S3_PUBLIC_BASE_URL=<value>
   S3_ACCESS_KEY=<value>
   S3_SECRET_KEY=<value>
   ```
   Save with Ctrl+O, Enter, and leave with Ctrl+X. Then let only you read it: `chmod 600 ~/foodstack-settings.env`.

## Check it worked

- Upload any small picture to the public bucket in the provider's console, then open `<S3_PUBLIC_BASE_URL>/<its file name>` in a private browser window: the picture shows. Delete it again.
- When you install ([guide 14](14-install.md)), FoodStack tests the buckets itself before it starts: it writes a small file to each, reads the public one through `S3_PUBLIC_BASE_URL` as a stranger would, and asks each bucket whether your two sites may upload. If anything is wrong, the installer says what to fix.
- Later, after you add a restaurant's logo in the back office, right-click it and open the image in a new tab: its address starts with your `S3_PUBLIC_BASE_URL`.

## Undo

Nothing on your server changes in this guide. Delete the buckets and the key at the provider if you want to start over.

## If something goes wrong

The installer, and `./logs.sh server` after it, start the message with "Storage check failed":

| Message | What to do |
|---|---|
| "the bucket '…' does not exist" | The name in `S3_PUBLIC_BUCKET` or `S3_PRIVATE_BUCKET` is mistyped, or `S3_ENDPOINT` / `S3_REGION` names another region than the bucket's |
| "the access key may not write to the bucket" | The key or secret is mistyped, or the key is not allowed on that bucket (Amazon: the policy in step 6; R2: the token's buckets; B2: the key's buckets and Read and Write) |
| "not readable by everyone" | The public bucket does not allow public reading (Amazon: steps 2 and 3; R2: step 2; B2: Public), or `S3_PUBLIC_BASE_URL` is not where its files are served |
| "does not allow uploads from https://…" | The CORS rule is missing on that bucket, or lists another address. Both buckets need it, with exactly your customer and back office addresses (`https://`, no slash at the end) |
| "not reachable at S3_ENDPOINT" | The address is mistyped, or the server cannot reach the internet |

## Move photos from this server

Installs made before FoodStack required your own buckets kept photos on the server. After an upgrade, `./upgrade.sh` warns "Photos are still kept on this server". To move them, with about 20 minutes offline:

1. Create the buckets and key as above.
2. Stop the apps, keep storage running, and take a backup:
   ```bash
   cd /opt/foodstack
   ./stop.sh proxy web backoffice server
   ./backup.sh
   ```
3. Copy the photos to your buckets. The old storage's keys are `S3_ACCESS_KEY` and `S3_SECRET_KEY` in `.env` (still the old ones):
   ```bash
   docker run --rm --network foodstack \
     -e RCLONE_CONFIG_OLD_TYPE=s3 -e RCLONE_CONFIG_OLD_PROVIDER=Other -e RCLONE_CONFIG_OLD_ENDPOINT=http://storage:9000 \
     -e RCLONE_CONFIG_OLD_ACCESS_KEY_ID='<old S3_ACCESS_KEY>' -e RCLONE_CONFIG_OLD_SECRET_ACCESS_KEY='<old S3_SECRET_KEY>' \
     -e RCLONE_CONFIG_NEW_TYPE=s3 -e RCLONE_CONFIG_NEW_PROVIDER=Other -e RCLONE_CONFIG_NEW_ENDPOINT='<S3_ENDPOINT>' \
     -e RCLONE_CONFIG_NEW_REGION='<S3_REGION>' \
     -e RCLONE_CONFIG_NEW_ACCESS_KEY_ID='<S3_ACCESS_KEY>' -e RCLONE_CONFIG_NEW_SECRET_ACCESS_KEY='<S3_SECRET_KEY>' \
     rclone/rclone:1.68.2 sh -c 'rclone copy -P old:foodstack-public new:<public bucket> && rclone copy -P old:foodstack-private new:<private bucket>'
   ```
4. Point the stored image addresses at the bucket, with [`tools/migrate/rewrite-media-urls.js`](../../tools/migrate/rewrite-media-urls.js) downloaded into `/opt/foodstack`. `OLD_BASE` is the old `S3_PUBLIC_ENDPOINT` in `.env` followed by `/foodstack-public`; `<MONGODB_PASSWORD>` is in `.env` too:
   ```bash
   docker run --rm --network foodstack -v "$PWD":/work -e OLD_BASE='<old S3_PUBLIC_ENDPOINT>/foodstack-public' \
     -e NEW_BASE='<S3_PUBLIC_BASE_URL>' mongo:8.2 \
     mongosh 'mongodb://foodstack:<MONGODB_PASSWORD>@mongodb:27017/?replicaSet=rs0&authSource=admin' --quiet --file /work/rewrite-media-urls.js
   ```
5. Stop the old storage, then change `.env`: delete the lines `FOODSTACK_TRIAL_STORAGE=true` and `S3_PUBLIC_ENDPOINT=…`, replace `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_PUBLIC_BUCKET` and `S3_PRIVATE_BUCKET` with your new values, and add `S3_ENDPOINT`, `S3_REGION`, `S3_PUBLIC_BASE_URL`, plus `S3_PATH_STYLE=false` for Amazon S3. Add `MEDIA_ORIGINS=` followed by the `https://host` part of `S3_PUBLIC_BASE_URL` and of `S3_ENDPOINT`, separated by a space; for Amazon S3 also `https://<public bucket>.s3.<region>.amazonaws.com https://<private bucket>.s3.<region>.amazonaws.com`.
   ```bash
   ./stop.sh storage
   nano .env
   echo '# Photos are in your buckets.' > config/storage.caddy
   ./start.sh
   ```
6. Check as above. Keep the old photos for a week (`docker volume ls` shows `foodstack_storage`), then delete them: `docker rm -f foodstack-storage-1; docker volume rm foodstack_storage`.
