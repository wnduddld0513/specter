# ROM integration (branch `seventeen`)

Fork of [dpejoh/specter](https://github.com/dpejoh/specter), licensed GPL-3.0 (see `LICENSE`).
Specter is a KernelSU module; this directory is its logic as a settings page of the ROM, with no
root, no module and no shell scripts. It lives in the Play Integrity Fix app of the ROM
(`io.github.wnduddld0513.pif`, see the `seventeen` branch of
[wnduddld0513/PlayIntegrityFix](https://github.com/wnduddld0513/PlayIntegrityFix)); these are
the Specter files of that app.

| File | Specter source |
| --- | --- |
| `rom/app/**/SpecterSync.java` | the update pass of `action.sh` and the scheduler of `service.sh`: GMS restart, target apps, security patch source (`security_patch.sh`), keybox step, preferred and blocked devices (`pif_apply_preferred`), ADB toggles |
| `rom/app/**/SpecterFragment.java`, `SpecterActivity.java`, `PatchDialog.java` | the web UI (`src/webroot`): steps, targets, patch, keybox, apply and ADB categories |
| `rom/app/res/xml/specter_settings.xml` | the layout of that page |
| `rom/app/**/KeyboxStore.java` | the keybox provider catalog, the Google revocation check and the bulletin date (`features/keybox.sh`, `keybox_info.sh`); shared with the TEESimulator page |

Settings keys: `Settings.Global lenovo_specter` (main switch, on unless 0). A change of the device,
the security patch source, the keybox provider or the targets is propagated to Play Integrity Fix and
to TEESimulator by `SpecterSync`.
