# Сборка APK «Нарезка»

Это обычный Android-проект на Gradle (Java, без AndroidX). Открывается любой средой, которая умеет
Gradle-проекты Android: Android Studio, сборщики на телефоне, GitHub Actions, онлайн-сборщики.

## Что нужно сборщику

* JDK 17;
* Android SDK Platform 34 (compileSdk 34), Build-Tools 34;
* интернет при первой сборке: Gradle 8.7, Android Gradle Plugin 8.2.2 (Google Maven),
  библиотеки распознавания речи `com.alphacephei:vosk-android:0.3.47` и `net.java.dev.jna:jna:5.13.0` (Maven Central).

## Перед сборкой

Интерфейс приложения лежит в `app/src/main/assets/web` и уже скопирован из `../tclip/static`.
Если меняли интерфейс в `tclip/static`, обновите копию:

```bash
bash tools/sync_web.sh
```

## Способы сборки

**Android Studio (компьютер).** File → Open → папка `android` → дождаться синхронизации Gradle →
Build → Build App Bundle(s) / APK(s) → Build APK(s). Файл: `app/build/outputs/apk/release/app-release.apk`
(или `debug/app-debug.apk`).

**Командная строка (компьютер или Termux с установленным Android SDK).**

```bash
./gradlew assembleRelease
```

Готовый файл: `app/build/outputs/apk/release/app-release.apk`.

**Сборщик на телефоне** (среды разработки Android-приложений из F-Droid/GitHub). Откройте папку
`android` как проект и запустите сборку `assembleRelease` (или «Build → Release»).
Нужен телефон с 4+ ГБ ОЗУ и около 3 ГБ свободного места.

**GitHub Actions (без компьютера).** Положите весь проект в свой репозиторий на GitHub. Файл
`.github/workflows/android-apk.yml` уже есть: вкладка **Actions** → «Сборка APK» → **Run workflow**.
Через 5–10 минут APK появится внизу страницы запуска в разделе **Artifacts**.

## Подпись

Сборка `release` подписывается отладочным ключом — такой APK сразу ставится на телефон
(«Разрешить установку из этого источника»). Для публикации в магазине приложений подпишите своим ключом
(`signingConfigs` в `app/build.gradle`).

## Что внутри

```
app/src/main/java/ru/tiktokclipper/core   логика без Android: расписание, субтитры, моменты, очередь,
                                          HTTP-сервер на 127.0.0.1, API (то же, что у Python-версии)
app/src/main/java/ru/tiktokclipper/app    Android: MediaCodec + OpenGL (сборка видео), звук, Vosk,
                                          папки через системное окно (SAF), галерея, служба, WebView
app/src/main/assets/web                   интерфейс (общий с веб-версией)
app/src/main/assets/fonts                 шрифт субтитров Montserrat ExtraBold (OFL)
tools/                                    тесты на обычной JVM, синхронизация интерфейса
```

Разрешения: интернет (скачать модель распознавания и проверить свежие видео YouTube), уведомления и
работа в фоне (служба с прогрессом), на Android 8–9 — запись в память для галереи. Доступа ко всей памяти
приложение не просит: видит только папки и файлы, которые вы выбрали в системном окне.

## Проверки без телефона

```bash
ANDROID_ALL=…/android-all-14-robolectric-10818077.jar VOSK_JAR=…/vosk.jar JNA_JAR=…/jna.jar bash tools/run_jvm_tests.sh
```

`android-all` (org.robolectric, Maven Central) нужен только как набор классов Android для компиляции и org.json.
