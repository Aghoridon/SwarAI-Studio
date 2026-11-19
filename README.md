# SwarAI Studio — Android APK Project (GitHub Actions Ready)

यह **SwarAI Studio Hybrid** (ऑफ़लाइन स्पीच इंजन + ऑनलाइन AI वॉइस क्लोनिंग + 20+ मिनट MP3 मर्ज + ऑडियो एडिटर) का संपूर्ण Android WebView प्रोजेक्ट है।

## इस APK की खास बातें:
1. **100% Working Native Mic:** `WebChromeClient` के ज़रिए फ़ोन का नेटिव माइक रिकॉर्डर और लाइव ब्राउज़र माइक दोनों काम करते हैं।
2. **Direct MP3 & JSON Downloads:** `AndroidBridge` के माध्यम से जनरेट हुई MP3 फ़ाइलें और बैकअप JSON सीधे फ़ोन के `Downloads/SwarAI_Studio/` फ़ोल्डर में सेव होते हैं।
3. **Permanent Local & IndexedDB Vault:** ऐप बंद करने या फ़ोन रीस्टार्ट करने पर भी आपकी API Key, 10+ क्लोन वॉइस प्रोफाइल और सेव की गई MP3 लाइब्रेरी सुरक्षित रहती है।

## GitHub Actions से APK कैसे बनाएं:
1. इस फ़ोल्डर की सभी फ़ाइलों को अपनी GitHub रिपॉज़िटरी में पुश करें।
2. GitHub रिपॉज़िटरी के **Actions** टैब में जाएं।
3. **Build SwarAI Studio APK** वर्कफ़्लो पूरा होने के बाद नीचे **Artifacts** सेक्शन से **`SwarAI-Studio-APK`** डाउनलोड करके अनज़िप करें और इंस्टॉल करें।
