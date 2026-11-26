# SIP для Телефона (Samsung S25)

Android-приложение с одной SIP-учётной записью. Оно подключает её к штатному интерфейсу «Телефон» через `ConnectionService`; сигнализацию и звук обеспечивает Linphone SDK. Экран настроек оформлен в стиле Samsung.

## Установка на подключённый телефон

1. Откройте установленное приложение **SIP**.
2. Откройте **Учётную запись SIP**. Введите логин, домен, пароль и при необходимости адрес прокси SIP. Для TLS используйте, например, `sip:pbx.example.org:5061;transport=tls` (провайдер должен поддерживать TLS).
3. Включите переключатель **SIP-вызовы** и разрешите доступ к микрофону.
4. Откройте **Учётные записи для вызовов** и включите SIP-аккаунт. На Samsung он может быть первоначально отключён.
5. В приложении **Телефон** выберите SIP-аккаунт при исходящем вызове, если интерфейс предлагает выбор.

Для приёма входящих вызовов SIP должен быть включён. После перезагрузки телефона включите его в приложении снова.

## Сборка

Требуются Android SDK (API 36), JDK 17+ и интернет для зависимости Linphone SDK. В PowerShell:

```powershell
$env:ANDROID_HOME = 'C:\Users\Spark108\AppData\Local\Android\Sdk'
$env:JAVA_HOME = 'C:\Program Files\BellSoft\LibericaJDK-21-Full'
$env:GRADLE_USER_HOME = "$PWD\.gradle-user"
.\gradlew.bat :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

`GRADLE_USER_HOME` указан отдельно, так как глобальный `~/.gradle/init.gradle` на этом компьютере перенаправляет все репозитории в локальный Nexus и не содержит Android Gradle Plugin.

## Ограничения

- Поддерживается одна SIP-учётная запись, аудиозвонки, ответ, завершение, удержание и DTMF. Видео, перевод звонка и SMS не реализованы.
- В разделе **Уведомления SIP** открываются системные настройки видимости. Android требует foreground-уведомление во время работы SIP-сервиса; оно может быть скрыто из шторки настройками Android, но системный индикатор активного сервиса остаётся под контролем ОС.
- Пароль хранится в Android Keystore, не экспортируется при резервном копировании. Сервис поддерживает SIP-прокси, указанный в форме. Без `transport=tls` сигнализация может идти без TLS; медиа-шифрование зависит от провайдера и его настроек.
- Linphone SDK распространяется по AGPLv3 либо коммерческой лицензии. Учитывайте это при распространении APK. См. [условия Linphone](https://www.linphone.org/en/liblinphone-voip-sdk/).

Основа интеграции: [Android Telecom](https://developer.android.com/develop/connectivity/telecom), [документация Linphone Android SDK](https://wiki.linphone.org/xwiki/wiki/public/view/Lib/Getting%20started/Android/).
