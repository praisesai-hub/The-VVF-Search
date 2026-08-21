<p align="center">
  <img src="docs/assets/logo.png" width="128" height="128" alt="VVF Smart Manager Logo">
</p>

# VVF Smart Manager 📱

> [!IMPORTANT]
> **🛡️ Privacy-first और local-by-default**: ऐप का स्थानीय file metadata, on-device analysis और vault data डिवाइस पर ही रखा जाता है। ऐप में वैकल्पिक cloud-sync integrations मौजूद हैं; वे केवल उपयोगकर्ता द्वारा authentication और sync action/configuration के बाद उपयोग किए जाने चाहिए। इसलिए इस README में "100% secure" या "पूरी तरह local-only" का blanket claim नहीं किया गया है—वास्तविक सुरक्षा release build, device configuration और runtime verification पर निर्भर करती है।

---

**VVF Smart Manager** एक आधुनिक, सुरक्षा-केंद्रित और फीचर-समृद्ध Android एप्लिकेशन है। इसे आधुनिक **Material Design 3** सिद्धांतों के साथ **Kotlin** और **Jetpack Compose** का उपयोग करके बनाया गया है। यह ऐप स्थानीय स्टोरेज को स्कैन करने, फाइलों को व्यवस्थित करने, संवेदनशील दस्तावेजों को सुरक्षित रखने और AI-आधारित डुप्लिकेट डिटेक्शन के लिए एक संपूर्ण समाधान प्रदान करता है।

---

## 🚀 मुख्य विशेषताएं (Key Features)

### 📂 स्मार्ट फाइल मैनेजर और स्कैनर
- **डायनामिक स्टोरेज विज़ुअलाइज़र**: एक सुंदर सर्कुलर डैशबोर्ड जो इमेज, वीडियो, ऑडियो और डॉक्यूमेंट्स के आधार पर उपयोग किए गए और खाली स्टोरेज को दिखाता है।
- **डीप फिजिकल स्कैनिंग**: मल्टी-थ्रेडेड स्कैनर जो स्थानीय फाइलों को इंडेक्स करता है और उनके मेटाडेटा को सुरक्षित **Room Database** में सिंक करता है।
- **बैकग्राउंड इंडेक्सर**: Android के **WorkManager** का उपयोग करके सिस्टम-फ्रेंडली बैकग्राउंड टास्क चलाता है ताकि फाइल इंडेक्सिंग हमेशा अपडेट रहे।

### 🔒 सुरक्षित एन्क्रिप्टेड वॉल्ट (Secure Vault)
- **Android Keystore-आधारित क्रिप्टोग्राफी**: Android **Keystore System** के भीतर जेनरेट की गई AES-GCM कुंजियों का उपयोग करके निजी फाइलों को सुरक्षित करता है। हार्डवेयर-backed क्षमता device runtime पर अलग से verify की जाती है।
- **विजुअल प्राइवेसी गार्ड**: पिन सुरक्षा, कस्टम कीबोर्ड लेआउट और निजी दस्तावेजों को इम्पोर्ट/एक्सपोर्ट करने के लिए विजुअल फीडबैक के साथ एक पूरी तरह से अलग छिपा हुआ वॉल्ट स्क्रीन।

### 🧠 AI डुप्लिकेट क्लीनर
- **TFLite-संचालित स्कैनिंग**: डिवाइस पर स्थानीय रूप से `mobile_clip_embedding` मॉडल का उपयोग करके हाई-डायमेंशनल वेक्टर्स की गणना करता है और सिमेंटिक डुप्लिकेट्स की पहचान करता है।
- **मेमोरी-सेफ फॉलबैक**: यदि TFLite लाइब्रेरी या मॉडल अनुपलब्ध हैं, तो यह स्वचालित रूप से पारंपरिक समानता मिलान (similarity matching) पर स्विच हो जाता है।
- **स्मार्ट रेमेडिएशन**: डुप्लिकेट्स की समीक्षा करें और उन्हें नियंत्रित Recycle Bin workflow में भेजें, जिसमें UI updates और recovery path शामिल हैं।

### 🔍 सिमेंटिक खोज और क्लाउड विस्तार
- **स्मार्ट सर्च**: इंडेक्स किए गए डॉक्यूमेंट हेडर और गुणों के साथ प्राकृतिक भाषा (natural-language) प्रश्नों का मिलान करके फाइलों को सिमेंटिक रूप से खोजें।
- **क्लाउड इंटीग्रेशन**: बाहरी cloud providers के लिए modular interfaces और provider implementations मौजूद हैं; प्रत्येक provider की production readiness अलग से verify करनी होगी।

---

## 🏗️ तकनीकी वास्तुकला (Technical Architecture)

यह एप्लिकेशन मुख्यतः **MVVM (Model-View-ViewModel)** pattern और layered architecture का उपयोग करता है:

- **UI Layer**: Jetpack Compose के साथ Material Design 3 गाइडलाइन्स, रिस्पॉन्सिव टच टारगेट (≥ 48dp), और स्पष्ट टाइपोग्राफिक पदानुक्रम।
- **Business Logic Layer**: `ViewModel` और `StateFlow` का उपयोग करके UI स्टेट्स (Loading, Success, Error) को स्ट्रीम किया जाता है।
- **Data Layer**: 
  - **Room Database**: स्थानीय SQL डेटा दृढ़ता (persistence) के लिए।
  - **KeystoreVaultManager**: Android Keystore और सुरक्षित फाइल स्ट्रीम पाइप्स को संभालने वाला क्रिप्टोग्राफिक मैनेजर।
  - **SemanticEmbeddingProvider**: स्थानीय ऑन-डिवाइस इन्फरेंस के साथ न्यूरल नेटवर्क एम्बेडिंग इंटरफेस।

---

## 🛠️ बिल्ड और सेटअप (Build and Setup)

### आवश्यकताएं (Prerequisites)
- **Android Studio Koala+** या नवीनतम कमांड लाइन टूल्स।
- आपके डेवलपमेंट एनवायरनमेंट में **JDK 17** कॉन्फ़िगर होना चाहिए।
- **Android API Level 24 (Android 7.0)** या उससे ऊपर चलने वाला डिवाइस या एमुलेटर।

### संकलन (Compilation)
एप्लिकेशन का डिबग वर्जन कंपाइल करने के लिए:
```bash
./gradlew :app:assembleDebug
```

### यूनिट टेस्ट (Running Unit Tests)
JVM-आधारित यूनिट और Robolectric टेस्ट चलाने के लिए:
```bash
./gradlew :app:testDebugUnitTest
```

### स्टेटिक एनालिसिस (Static Analysis)
कोड फॉर्मेटिंग और सुरक्षा की जांच के लिए:
```bash
./gradlew :app:lintDebug
```

---

## 🛡️ सुरक्षा और अनुपालन (Security & Compliance)

यह प्रोजेक्ट **OWASP MASVS** guidance के संदर्भ में security controls लागू करने के लिए डिज़ाइन किया गया है। यह स्वयं किसी independent compliance certification का दावा नहीं करता। सुरक्षा जांच के लिए `scripts/security_compliance_check.py` का उपयोग करें।

---

## 🤖 निरंतर एकीकरण (CI/CD)
यह रिपॉजिटरी **GitHub Actions** के साथ पूरी तरह से कॉन्फ़िगर है (`.github/workflows/android.yml`) जो हर पुश या पुल रिक्वेस्ट पर स्वचालित रूप से बिल्ड और टेस्ट चलाती है।

---

## 📄 लाइसेंस और श्रेय
Kotlin और Jetpack Compose के साथ बनाया गया। Release से पहले CI checks, device validation और privacy/security review आवश्यक हैं।
