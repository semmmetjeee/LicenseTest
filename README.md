# LicenseTest

Paper 1.21.x Maven test plugin using `semmmetje.nl` license validation.

## Behavior
- First boot creates only `plugins/LicenseTest/license.yml`.
- Put a license key in it and restart.
- Before `onEnable`, the plugin calls the license API.
- If invalid/unreachable/over the instance limit, the plugin stays disabled.
- `config.yml` is only created after a successful license validation.
- A local `.instance` UUID identifies the installation.
- The API additionally stores a hashed machine fingerprint.

## Build
```bash
mvn clean package
```

## Integration in another plugin
Copy `LicenseManager.java`, change:
```java
new LicenseManager(this, "your-product-id", "https://semmmetje.nl/api/license/validate.php");
```
Then run it in `onLoad()` and guard `onEnable()`.

## Security note
No Java plugin distributed to customers can be literally un-bypassable: a determined user can decompile and patch the JAR. This implementation fails closed and keeps entitlements/instance limits server-side. For stronger protection, combine it with obfuscation, signed responses, short-lived leases, and server-side features/assets.
