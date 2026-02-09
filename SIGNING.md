## 1. How to Generate a Key (New Keystore)

If you don't have a keystore yet, run this command in your terminal (`app/` folder):

```powershell
keytool -genkey -v -keystore your_keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
```

*Follow the prompts (set a password, name, etc.). This will create `your_keystore.jks`.*

## 2. Fast Track (Local Builds)

To sign builds on your local machine, create a file named `keystore.properties` in the `app/` (root of app repo) folder:

```properties
storeFile=your_keystore.jks
storePassword=your_keystore_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

Place your `.jks` keystore file in the same folder.

## 2. GitHub Actions (Automatic Release)

To enable automatic signing and releases in GitHub Actions, you must add the following **Secrets** to your GitHub repository (`Settings` -> `Secrets and variables` -> `Actions`):

| Secret Name | Description |
| :--- | :--- |
| `KEYSTORE_FILE` | The **Base64** encoded string of your `.jks` file. |
| `KEY_ALIAS` | The alias of your key. |
| `KEYSTORE_PASSWORD` | The password for your keystore. |
| `KEY_PASSWORD` | The password for the specific key. |

### How to get Base64 string:
On Windows (PowerShell):
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("your_keystore.jks")) | Out-File -FilePath keystore_base64.txt
```
Copy the content of `keystore_base64.txt` into the `KEYSTORE_FILE` secret.

## 3. Security Warning
> [!CAUTION]
> Never commit your `.jks` files or `keystore.properties` to the repository. They are already added to `.gitignore`.
