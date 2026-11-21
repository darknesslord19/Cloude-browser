# Cloude Browser

Claude'un uzaktan kullanabildiği Android tarayıcısı. Üç parçası var:

1. **Android uygulaması** (`app/`): WebView tabanlı küçük tarayıcı. Arka planda aracı sunucuyu yoklar ve gelen komutları çalıştırır.
2. **Aracı sunucu** (`relay/`): Claude ile telefon arasındaki köprü. Claude'a MCP aracı olarak görünür.
3. **GitHub Actions** (`.github/workflows/build.yml`): Her push'ta APK'yı derler ve **Releases > latest** altına koyar.

```
Claude  --MCP-->  aracı sunucu  <--yoklama--  telefondaki uygulama
```

## Kurulum

### 1. APK'yı indir
Depoya push edince Actions APK'yı derler. **Releases > latest** sayfasından `cloude-browser.apk` dosyasını telefona indirip kur ("bilinmeyen kaynaklardan kuruluma izin ver" gerekebilir).

### 2. Aracı sunucuyu yayınla
[![Render'a yükle](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/darknesslord19/Cloude-browser)

Düğmeye bas ve Render hesabınla onayla. `render.yaml` sunucuyu kurar ve `TOKEN` değerini **kendisi üretir**.
Kurulum bitince Render panelinde **Environment** bölümünden `TOKEN` değerini kopyala.

Bağlantı adresin şu olur (iki yerde de aynısını kullanacaksın):

```
https://cloude-browser-relay.onrender.com/mcp/<TOKEN>
```

Başka bir yerde çalıştırmak istersen: `relay/` klasörü Docker ile ya da `TOKEN=gizli node server.js` ile çalışır.

### 3. Uygulamayı bağla
Uygulamayı aç, ⚙ düğmesine bas ve yukarıdaki bağlantı adresini yapıştır. Üstte yeşil nokta "Bağlı" göstermeli. Uygulama açık ve ekran uyanık olmalı.

### 4. Claude'a ekle
claude.ai > Ayarlar > Bağlayıcılar > Özel bağlayıcı ekle (hesap planına göre değişebilir): aynı bağlantı adresini yapıştır.
Sonra Claude'a "şu siteyi tarayıcıdan aç ve analiz et" demen yeterli.

## Claude'un kullanabildiği araçlar

| Araç | Ne yapar |
| --- | --- |
| `browser_navigate` | Adres açar |
| `browser_info` | Adres ve başlık |
| `browser_get_text` | Sayfa metni |
| `browser_get_links` | Bağlantı listesi |
| `browser_report` | Güvenlik özeti: formlar, şifre alanı, dış betikler, iframe'ler, indirme bağlantıları |
| `browser_click` / `browser_type` / `browser_scroll` / `browser_back` | Sayfayla etkileşim |
| `browser_screenshot` | Ekran görüntüsü |
| `browser_eval` | Sayfada JavaScript çalıştırır |

## Güvenlik notları

- `TOKEN` bağlayıcı adresinin içindedir. Adresi bilen herkes tarayıcını kontrol edebilir. Kimseyle paylaşma, sızarsa değiştir.
- Uygulama dosya indirmelerini engeller ve `http/https` dışındaki adresleri açmaz.
- Şüpheli sitelerde şifre ya da kart bilgisi girme. Uygulamadaki oturumlar telefonundaki diğer tarayıcılardan ayrıdır.
