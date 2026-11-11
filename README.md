# Пингвин

Android-приложение с полноценным Linux (Debian 13) внутри: без root, через PRoot. Внутри — рабочий стол, Firefox ESR, Wine (Hangover) для Windows-программ, установка `.deb`, AppImage, архивов и `.exe`.

## Скачать

- Релизы: https://github.com/ivanivorontsov-sudo/pingvin/releases
- Свежий APK: [Пингвин 1.15](https://github.com/ivanivorontsov-sudo/pingvin/releases/tag/v1.15)

Нужен телефон или планшет на Android 8+; лучше **arm64** (почти все современные устройства). Первый запуск распаковывает Debian и встроенные пакеты — это может занять несколько минут.

## Возможности

- Терминал Debian и графический рабочий стол (встроенный дисплей).
- Браузер Linux: Firefox ESR (встроен).
- Браузер Windows: K-Meleon через встроенный Hangover Wine 11.16.
- Установка программ **из файла**: `.deb`, AppImage, `.tar.*`, `.zip`, `.exe`, `.msi`.
- x86_64 Linux-программы запускаются через box64.
- DOS-программы (DOSBox-X) и эмуляторы консолей.

## Тихо для ПК

Сборки «Тихо для ПК» (архив Firefox ESR с [tiho-updates](https://github.com/ivanivorontsov-sudo/tiho-updates/releases/tag/desktop-v153.6.0) или установщик Electron `.deb` / AppImage / `.exe`) на телефоне **нельзя запустить как есть**: это x86_64 Electron или x86_64 Firefox, и через box64/Wine они сразу закрываются (песочница Chromium/Gecko, GPU, эмуляция).

Пингвин 1.15 ставит их как ярлык **«Тихо»** на встроенном родном Firefox ESR (arm64) с политиками приватности Тихо (uBlock Origin, защита от слежки). Так работают и Linux-файл, и Windows-файл.

Если после запуска окно сразу исчезло, нажмите **«Лог»** на дисплее — там будет причина, а не молчаливый выход.

Для настоящего окна Electron «Тихо» на телефоне нужна сборка Linux **aarch64** (см. релизы tiho-updates) — её Пингвин ставит и запускает нативно, с флагами `--no-sandbox` и без GPU.

## Сборка из исходников

Большие архивы (`rootfs-aarch64.tar.xz`, `gui-debs-aarch64.tar`, `browser-debs-aarch64.tar`, `wine-aarch64.tar.xz`) лежат в релизе и копируются в `app/src/main/assets/` (в git их нет). Затем:

```
./gradlew :stub:assembleRelease :app:assembleRelease
```

Подпись — тем же ключом, что и 1.14, чтобы обновление устанавливалось поверх.
