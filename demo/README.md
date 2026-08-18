# Telegram Saved Messages Drive

A Spring Boot web application that treats your Telegram **Saved Messages** as a private file store. It logs in to a Telegram user account through TDLib and represents every file and virtual folder with a versioned Telegram caption.

## What it supports

- QR-code and phone-number sign-in with your Telegram API ID and API hash
- A responsive Drive-style folder browser with breadcrumbs and browser back/forward support
- Virtual folders, uploads of files and directory trees, rename, move, recursive copy, and delete
- Search across files and folders, multi-select actions, and live total-storage calculation
- Logout that closes the Telegram account session on the server

## Run it locally

1. Install Java 17 and the Microsoft Visual C++ Redistributable (the Telegram native library needs it on Windows).
2. Create a Telegram application at [my.telegram.org](https://my.telegram.org) and keep its API ID and API hash private.
3. From this project folder, start the app:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

4. Open [http://localhost:8080](http://localhost:8080), enter your Telegram API credentials, and choose QR or phone login.

The first startup downloads the TDLight/TDLib dependencies. Session databases and short-lived upload staging files are kept under `telegram-drive-data/`, which is ignored by Git.

## Notes

- This uses a **Telegram user account**, not a bot; Saved Messages is only available to the account itself.
- Folder structure is virtual. A tiny marker document is sent for each folder, while a compact, versioned caption stores the item ID, parent ID, name, type, and size. This lets move, rename, copy, and search work without an external database.
- The current web upload cap is 200 MB and can be changed with `spring.servlet.multipart.max-file-size` and `spring.servlet.multipart.max-request-size` in `application.properties`.
- Use HTTPS and restrict access before deploying this anywhere other than your own machine. API credentials are sent only for the active server session and are not written by the browser to local storage.
- The Maven configuration includes the Windows AMD64 native artifact. For another operating system or CPU architecture, replace the `tdlight-natives` classifier in `pom.xml` with the matching TDLight artifact.
