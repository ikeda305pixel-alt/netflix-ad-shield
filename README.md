# Netflix Ad Shield

Android公式Netflixアプリ（`com.netflix.mediaclient`）の**広告再生中だけ自動で消音＆真っ黒な省電力カバーを表示**する、自分用Androidコンパニオンアプリです。

**PayPay等の決済アプリとの両立モード**を搭載しており、Netflixをフォアグラウンドで表示している間だけ自動で自身のユーザー補助サービスをONにし、ホーム画面やPayPayへ移動した瞬間に自動でOFFへ戻します。

## 主な機能

1. **PayPay両立モード（ユーザー補助の自動ON/OFF）**
   - `NetflixWatchdogService`（軽量 ForegroundService）が `UsageStatsManager` で1.5秒間隔のフォアグラウンド判定を実施。
   - Netflix（`com.netflix.mediaclient`）表示中のみ `NetflixAdShieldService` を自動ON、PayPay（`jp.ne.paypay.android.app`）やホーム画面へ移動したら遅延なしで即OFF。
   - SystemUI（音量バー等）の一瞬の表示のみ1回（1.5秒）猶予し、画面消灯時・サービス終了時には確実にOFFへ戻します。
   - 他のアプリが有効にしているユーザー補助サービスは一切破壊しません。
2. **Shizuku内蔵・PC不要＆コマンド不要ワンタップ権限付与（v1.2.0）**
   - アプリ内の「Shizukuで権限を一括付与」ボタンを1回押すだけで、`WRITE_SECURE_SETTINGS` と `GET_USAGE_STATS` を自動付与します。
   - 1回付与すれば再起動後も保持されるため、完了後は Shizuku やワイヤレスデバッグをOFFにしてもそのまま使えます。
3. **自動ミュート＆真っ黒な省電力カバー**
   - 広告検知時に自動消音し、画面全体を真っ黒カバーで覆って残り秒数（例: `2本中1本目の広告を再生中です。残り23秒です`）をライブ表示。広告終了時に自動で元の音量へ復元します。

## 初回セットアップ（PC不要・コマンド入力ゼロ）

1. [Releases](https://github.com/ikeda305pixel-alt/netflix-ad-shield/releases) から `netflix-ad-shield-v1.2.0.apk` をスマホへインストールします。
2. Google Play から **Shizuku** をインストールし、ワイヤレスデバッグでペアリングして **「開始」** を押します（初回のみ）。
3. **Netflix Ad Shield** を開き、以下を順番にタップします：
   - **「Shizukuで権限を一括付与（PC・コマンド不要）」** → Shizuku の確認画面で **「常に許可」**（または許可）
   - **「他のアプリの上に重ねて表示」** → 許可
   - **「自動切替（PayPay両立モード）を開始」**
4. 完了後はワイヤレスデバッグや Shizuku をOFFに戻しても権限は維持されます。

## PC（Windows + USB接続）でセットアップする場合

```powershell
powershell -ExecutionPolicy Bypass -File .\setup_paypay_safe.ps1
```
