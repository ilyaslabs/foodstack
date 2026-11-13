# 12 Storage for photos and files

[Back to the handbook](README.md)

FoodStack keeps every photo and file (restaurant logos, dishes, review photos, riders' papers) at a storage provider, never on your server. You create two **buckets** there, which are folders in the provider's cloud:

- a **public** bucket, for images anyone may see, such as dishes and logos;
- a **private** bucket, for files only signed-in staff may open, such as riders' papers.

Browsers upload straight to the buckets and load images from them, so your server's disk only holds the database. The same buckets stay with you when you grow to Kubernetes later.

Do this guide before [installing](14-install.md): what you note here goes in `config/application.yml`, and FoodStack checks the buckets every time it starts.

## What you need

- An account at one storage provider. This guide covers three; any S3-compatible provider works the same way.

  | Provider | Good to know |
  |---|---|
  | **Amazon S3** | The best known. You pay for storage and for every download |
  | **Cloudflare R2** | No download charges. Images are served from a name on your own domain, which must use Cloudflare's DNS |
  | **Backblaze B2** | Low storage prices |

- Your customer and back office addresses, for example `https://example.com` and `https://admin.example.com` ([guide 11](11-domain-and-dns.md)).
- About 30 minutes.

Write down these seven values as you go, in your password manager, in a note named "FoodStack storage". The names are the settings under `foodstack.storage` in `config/application.yml`:

| Name | What it is |
|---|---|
| `endpoint` | The provider's S3 address |
| `region` | The buckets' region |
| `public-bucket` | The public bucket's name |
| `private-bucket` | The private bucket's name |
| `public-base-url` | Where the public bucket's files are served |
| `access-key` | The key's id |
| `secret-key` | The key's secret |

Bucket names are shared by everyone on Amazon S3 and Backblaze B2, so pick names with your business in them, such as `tasty-foodstack-public` and `tasty-foodstack-private`.

## Steps

Follow the part for your provider.

### Amazon S3

1. Sign in to the [AWS console](https://console.aws.amazon.com/s3/) and choose a region near your customers (top right, for example `eu-west-1` or `me-central-1`). Note it as `region`.
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
   ```yaml
   endpoint: https://s3.<region>.amazonaws.com
   region: <region>
   path-style: false
   public-base-url: https://<public bucket>.s3.<region>.amazonaws.com
   ```

### Cloudflare R2

1. In the [Cloudflare dashboard](https://dash.cloudflare.com), open **R2 Object Storage** and **Create bucket** twice: the public one and the private one (location: automatic).
2. **Give the public bucket an address**: open it, **Settings**, **Public access**, **Custom Domains**, **Connect domain**, and enter a name on your domain such as `media.example.com`. Cloudflare adds the DNS record itself. Note `https://media.example.com` as `public-base-url`. (The `r2.dev` address Cloudflare also offers is slowed down on purpose and is for testing only.)
3. Leave the private bucket's public access off.
4. **Allow uploads from your sites**, on **both** buckets: **Settings**, **CORS Policy**, **Add CORS policy**, and paste the same JSON as in Amazon S3, step 5.
5. **Make a key for FoodStack**: back on the R2 page, **Manage API tokens** (or **API**), **Create API token**. Choose **Object Read & Write**, limit it to your two buckets, and create it. Note the **Access Key ID**, the **Secret Access Key** and the S3 address shown under it (`https://<account id>.r2.cloudflarestorage.com`). The secret is shown once.
6. Your values:
   ```yaml
   endpoint: https://<account id>.r2.cloudflarestorage.com
   region: auto
   path-style: true
   public-base-url: https://media.example.com
   ```

### Backblaze B2

1. Sign in to [Backblaze](https://secure.backblaze.com/b2_buckets.htm), **Buckets**, **Create a Bucket**: the public one with **Files in Bucket are: Public**, then the private one with **Private**.
2. Each bucket's card shows its **Endpoint**, such as `s3.us-west-004.backblazeb2.com`. The part after `s3.` and before `.backblazeb2.com` is the region (`us-west-004`).
3. **Allow uploads from your sites**, on **both** buckets: **CORS Rules**, choose **Share everything in this bucket with all HTTPS origins**, and under **Apply CORS rules to the following** choose **S3 Compatible API**. Save. (Uploads still need a link FoodStack signs, so this does not let anyone else upload.)
4. **Make a key for FoodStack**: **Application Keys**, **Add a New Application Key**, name it `foodstack`, allow access to **All** buckets (one key must reach both), choose **Read and Write**, and create it. Note the **keyID** and the **applicationKey**. The key is shown once.
5. Your values:
   ```yaml
   endpoint: https://s3.<region>.backblazeb2.com
   region: <region>
   path-style: true
   public-base-url: https://s3.<region>.backblazeb2.com/<public bucket>
   ```

### Any provider: put the values in your settings

8. In the FoodStack folder on your server ([guide 14](14-install.md) gets it), fill in the
   `storage` part of `config/application.yml`:
   ```bash
   nano config/application.yml
   ```
   ```yaml
   foodstack:
     storage:
       endpoint: <value>
       region: <value>
       path-style: <false for Amazon S3, true for R2 and B2>
       access-key: <value>
       secret-key: <value>
       public-bucket: <value>
       private-bucket: <value>
       public-base-url: <value>
       public-endpoint: <the same as endpoint>
       check-origins: https://example.com,https://admin.example.com
   ```
   Save with Ctrl+O, Enter, and leave with Ctrl+X.
9. The back office loads photos from the public bucket and uploads to the S3 address, so it must
   know both. In `.env`, set `MEDIA_ORIGINS` to the `https://host` part of `public-base-url` and of
   `endpoint`, separated by a space. For Amazon S3 also add
   `https://<private bucket>.s3.<region>.amazonaws.com`:
   ```
   MEDIA_ORIGINS=https://tasty-foodstack-public.s3.eu-west-1.amazonaws.com https://s3.eu-west-1.amazonaws.com https://tasty-foodstack-private.s3.eu-west-1.amazonaws.com
   ```

**Separate services** ([deploy/services](../../deploy/services/README.md)): the same block goes in
`media.yml`. **Kubernetes** ([guide 40](40-move-to-kubernetes.md)): `datastores.s3` in your values file.

## Check it worked

- Upload any small picture to the public bucket in the provider's console, then open `<public-base-url>/<its file name>` in a private browser window: the picture shows. Delete it again.
- When you install ([guide 14](14-install.md)), FoodStack tests the buckets itself before it starts: it writes a small file to each, reads the public one through `public-base-url` as a stranger would, and asks each bucket whether your two sites may upload. If anything is wrong, the server stops and `docker compose logs server` says what to fix.
- Later, after you add a restaurant's logo in the back office, right-click it and open the image in a new tab: its address starts with your `public-base-url`.

## Undo

Nothing runs differently until you start FoodStack. Delete the buckets and the key at the provider if you want to start over.

## If something goes wrong

`docker compose logs server` shows a message starting with "Storage check failed":

| Message | What to do |
|---|---|
| "the bucket '…' does not exist" | The name in `public-bucket` or `private-bucket` is mistyped, or `endpoint` / `region` names another region than the bucket's |
| "the access key may not write to the bucket" | The key or secret is mistyped, or the key is not allowed on that bucket (Amazon: the policy in step 6; R2: the token's buckets; B2: the key's buckets and Read and Write) |
| "not readable by everyone" | The public bucket does not allow public reading (Amazon: steps 2 and 3; R2: step 2; B2: Public), or `public-base-url` is not where its files are served |
| "does not allow uploads from https://…" | The CORS rule is missing on that bucket, or lists another address. Both buckets need it, with exactly your customer and back office addresses (`https://`, no slash at the end) |
| "not reachable at …" | The address is mistyped, or the server cannot reach the internet |

After fixing `config/application.yml`, run `docker compose restart server`. After changing
`MEDIA_ORIGINS` in `.env`, run `docker compose up -d`.
