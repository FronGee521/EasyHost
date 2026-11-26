# EasyHost

Android local website host manager (Kotlin + Jetpack Compose).

## Current implementation
- Dark minimal UI
- Select a project root using Android Storage Access Framework
- Scan child folders as individual projects
- Start/stop an HTTP server and configure its port
- Serve static HTML, CSS, JavaScript, JSON and common image assets

## PHP status
PHP folders are detected (`index.php`), but PHP execution is **not enabled in this starter build**. Android does not include PHP. A real PHP mode requires bundling a PHP-CGI runtime for supported Android ABIs (for example arm64-v8a and armeabi-v7a), configuring its extensions and process lifecycle, and routing `.php` requests through CGI/FastCGI. This repository deliberately returns HTTP 501 for PHP files rather than pretending to execute them.

## Build
Open this folder in Android Studio (JDK 17), allow Gradle sync, then Build > Build Bundle(s) / APK(s) > Build APK(s).

## Storage
On first launch, choose `Documents/Host` (create it in the Android file picker if it does not exist). Add one folder per site, with `index.html` for static sites.

## Notes
The current server is an in-app foreground-free prototype. Android may stop it when the app is backgrounded or the process is reclaimed. A production release should add a foreground service with a persistent notification, plus explicit lifecycle handling and LAN access controls.


## GitHub Actions: автоматическая сборка APK

В репозитории уже добавлен workflow `.github/workflows/android.yml`.
После отправки кода в ветку `main` или `master` GitHub Actions автоматически
соберёт debug APK. Сборку также можно запустить вручную во вкладке **Actions**
через workflow **Android APK**.

Готовый файл появится в разделе **Artifacts** у завершённого запуска workflow
под именем `EasyHost-debug-apk`. Это debug-сборка для установки и тестирования,
а не подписанный релиз для Google Play.

## Уведомление о работающем сервере

Когда сервер успешно запускается, EasyHost показывает постоянное системное
уведомление с названием проекта и локальным адресом. При остановке сервера
уведомление убирается. На Android 13 и новее приложение запрашивает разрешение
на уведомления; если пользователь запретит его, статус всё равно будет виден
в интерфейсе приложения.

Важно: это уведомление само по себе не превращает сервер в Android foreground
service. Поэтому Android может ограничивать или завершать серверный процесс,
если приложение долго находится в фоне. Для гарантированной фоновой работы
потребуется отдельный foreground service.
