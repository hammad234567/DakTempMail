# Dak Temp Mail (Android app)

Aapke apne Gmail se temporary addresses banata hai (dot trick: `a.b.c@gmail.com`, ya plus trick: `name+abc123@gmail.com`). In sab par aane wali mail aapke apne Gmail inbox mein aati hai, aur app unhe address ke hisaab se dikhati hai. Server ki zaroorat nahi, app seedha Gmail IMAP se baat karti hai.

## Features
- Real Gmail address generate (Dot ya Plus)
- Copy, Naya address, Save
- Saved list se Restore
- Backup copy aur Restore (Settings mein)
- Inbox har 10 second mein refresh, mail khol kar parh sakte hain

## Gmail ki tayyari (ek baar)
1. Google Account mein **2-Step Verification** on karein.
2. `myaccount.google.com/apppasswords` kholen, ek App Password banayein (16 letters).
3. App ke Settings mein apna Gmail aur ye App Password daalein. Asli Gmail password kabhi na daalein.

Behtar hai ke is kaam ke liye ek alag naya Gmail account use karein.

## APK GitHub se banana
1. github.com par naya repository banayein (naam: DakTempMail).
2. Is ZIP ke andar ki saari files upload karein. `.github` folder bhi shamil hona chahiye.
3. Repository mein **Actions** tab kholen. "Build APK" apne aap chal jayega (2-5 minute).
   Na chale to "Build APK" par click karke **Run workflow** dabayein.
4. Build hone ke baad us run ko kholen, neeche **Artifacts** mein `DakTempMail-apk` download karein.
5. ZIP kholne par `app-debug.apk` milegi. Phone mein install karein (Unknown sources allow karni parhegi).

## Zaroori baatein
- Dot aur plus addresses sab aapke ek hi Gmail ke hain. Jo sites Gmail ke dots aur plus ko pehchan kar same account maan leti hain, wo inhe block kar sakti hain.
- Ek hi insaan ke kayi fake accounts banana kisi bhi site ke rules ke khilaf ho sakta hai. Zimmedari se use karein.
- App sirf INBOX ki aakhri 300 mails mein dhoondti hai.
- App Password phone mein encrypted save hota hai. Backup mein shamil nahi hota.
