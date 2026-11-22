# Lia AI — Free Setup (Roman Urdu)

Ye CodeNinjaVik ki Lia AI hai — public GitHub repo se li gayi hai.
README khud kehta hai: clone karo, Android Studio mein kholo, build karo,
apni Gemini key Settings mein daalo. Koi key code mein nahi hai.

## Lia AI kya hai?
- Voice-first Android AI companion, Gemini Live API se real-time awaz mein baat
- Text chat (Gemini 2.0 Flash), 10 personalities, Hindi / Hinglish / English
- Website Forge ("ek cafe ki website banao" bolne se website banata hai)
- Phone actions: apps kholna, calls, SMS/WhatsApp composer
- 3D orb UI, key sirf phone mein save hoti hai

## Phone par APK banane ke 2 tareeqe

### Tareeqa 1 — GitHub Actions (asan, Maya jaisa)
1. GitHub par naya repo banao (jaise `LiaAI`).
2. Is folder ki files upload karo (zip `lia-ai.zip` se).
3. GitHub repo mein **Actions** tab kholo → **Build Lia AI APK** → **Run workflow**.
4. Build complete hone par **Artifacts** se `lia-ai-direct-debug-apk` download karo.
5. APK phone mein install karo.

### Tareeqa 2 — Android Studio (PC par)
1. Android Studio kholo → is folder ko Open karo.
2. Build → `directDebug` ya `directRelease` build karo.
3. APK phone mein install karo.

## Gemini key (FREE)
1. Google AI Studio (aistudio.google.com) se FREE Gemini API key banao.
   (Nayi keys `AQ.` se shuru hoti hain.)
2. App kholo → Settings → key paste karo. Key sirf tumhare phone mein
   rehti hai, kahin nahi bheji jati.
3. Bas — baat karna shuru karo!

## Sachi baatein (honest notes)
- License/access-key system is code mein sirf LOCAL hai — default Active/Free
  hai, koi server check nahi hai. Koi paid key khareedne ki zaroorat nahi.
- Ye APK mere Linux par build nahi ho sakta (Android SDK nahi hai) — is liye
  GitHub Actions ya Android Studio se build karna hoga.
- Real-time voice (Gemini Live) ke liye internet chahiye; free Gemini tier
  ki per-minute limits hoti hain.
- "Direct" flavor full features wala hai; "Play" flavor Play Store-safe hai.
