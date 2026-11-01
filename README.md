# Bulk Mailer (Android)

Sends a personalised email to everyone on a list from your Gmail account. Each person gets their own separate email, and you can send repeat copies.

## Features
- Paste recipients (`email, name` one per line, or `Name <email>`) or import a CSV with a header row (`email,name,company,...`)
- Fill-ins: `{name}`, `{firstname}`, `{email}`, any CSV column (`{company}`), fallbacks (`{firstname|there}`)
- **Copies per person (1–100)**: sends the same email several times to each address, e.g. a reminder. Each round goes through the whole list, then waits the time you set (minutes, hours or days; minimum 1 minute) before the next round. The phone must stay on and online until the last round. Use `{copy}` / `{copies}`, e.g. subject `Reminder {copy} of {copies}`
- Attachments (up to about 25 MB total)
- Gap between emails (default 5 s), so Gmail doesn't flag you
- Preview, "Test to me", Stop, and Retry failed
- Keeps sending with the screen off (progress shows in the notification bar)

## 1. Get the APK

### Option A: let GitHub build it (no software needed)
1. Create a free account at github.com, then click **New repository** (it can be Private) and create it.
2. On the new repo page, click **uploading an existing file**. Unzip `BulkMailer.zip` and drag **everything inside the BulkMailer folder** in, including the hidden `.github` folder. (On Windows, turn on View → Hidden items. On a Mac, press Cmd+Shift+. ). Then click **Commit changes**.
   - If the `.github` folder won't upload, create the file manually: **Add file → Create new file**, name it `.github/workflows/build.yml`, and paste in the contents of that file.
3. Open the **Actions** tab and wait about 5 minutes for "Build APK" to show a green tick.
4. Click the run, scroll to **Artifacts**, and download **BulkMailer-apk**. Unzip it to get `app-debug.apk`.

### Option B: Android Studio
Open the `BulkMailer` folder in Android Studio, let it sync, then use **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

## 2. Install
Copy `app-debug.apk` to your phone, tap it, and allow "install from this source" when asked.

## 3. Gmail App Password (one-time)
1. Turn on 2-Step Verification on your Google account.
2. Go to https://myaccount.google.com/apppasswords (the app has a button for this), create one called "Bulk Mailer", and copy the 16 letters.
3. Paste it into the app's "App password" box. Your normal Gmail password won't work.

## Limits
- Personal Gmail can send about **500 emails a day** (every repeat copy counts). Google Workspace accounts can send about 2,000.
- Sending lots of identical repeats can get an account flagged as spam, so keep copies low and only send them to people who expect them.
- Your details are stored only on your phone.
