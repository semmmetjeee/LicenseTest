# LicenseTest

Paper 1.21.x Maven test plugin for the Mars Development v2 licensing platform.

## Product / API
- Product ID: `license-test`
- Current test endpoint: `http://marsdevelopment.gt.tc/api/license/validate.php`

## Behavior
- First boot creates only `plugins/LicenseTest/license.yml`.
- Put a license key in it and restart.
- Before enabling, the plugin validates the key online.
- `config.yml` is only created after a successful license validation.
- A persistent local `.instance` UUID identifies each installation.
- The server-side API enforces the product, owner status and maximum active instances.
- After enabling, the plugin re-checks the license every 5 minutes.
- A revoked, renewed, blocked or globally disabled license immediately disables the plugin on its next check and prints a clear console error.
- Temporary network/API errors are retried; after 3 consecutive failed checks the plugin disables as a safety measure.

## Build
```bash
mvn clean package
```

## Integration in another plugin
Copy `LicenseManager.java` and create it with your own product slug:
```java
new LicenseManager(this, "your-product-id", "http://marsdevelopment.gt.tc/api/license/validate.php");
```
Validate before normal startup, only create your normal configs after the validation succeeds, and call `startMonitoring()` after enabling.

## Security note
No Java plugin distributed to customers can be literally un-bypassable because a determined user can decompile and patch a JAR. This implementation keeps authoritative entitlement/revocation/instance state server-side and fails closed. Use HTTPS before production; obfuscation and signed server responses can be layered on later.
