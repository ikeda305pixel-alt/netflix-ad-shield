# Netflix Ad Shield

Android公式Netflixアプリ（`com.netflix.mediaclient`）の**広告再生中だけ自動で消音＆真っ黒な省電力カバーを表示**する、自分用Androidコンパニオンアプリです。

**PayPay等の決済アプリとの両立モード**を搭載しており、Netflixをフォアグラウンドで表示している間だけ自動で自身のユーザー補助サービスをONにし、ホーム画面やPayPayへ移動した瞬間に自動でOFFへ戻します。

DRM回避・通信改変・広告スキップ等は一切行わず、Android標準API（`AccessibilityService`・`UsageStatsManager`・`AudioManager`・`SYSTEM_ALERT_WINDOW`）のみで安全に動作します。

## 主な機能

1. **PayPay両立モード（ユーザー補助の自動ON/OFF）**
   - `NetflixWatchdogService`（軽量 ForegroundService）が `UsageStatsManager` で1.5秒間隔のフォアグラウンド判定を実施。
   - Netflix（`com.netflix.mediaclient`）表示中のみ `NetflixAdShieldService` を自動ON、PayPay（`jp.ne.paypay.android.app`）やホーム画面へ移動したら遅延なしで即OFF。
   - SystemUI（音量バー等）の一瞬の表示のみ1回（1.5秒）猶予し、画面消灯時・サービス終了時には確実にOFFへ戻します。
   - 他のアプリが有効にしているユーザー補助サービスは一切破壊しません（コロン区切りセット上で自コンポーネントのみ追加/削除）。
2. **自動ミュート＆音量復元**
   - 広告検知時にメディア音量（`STREAM_MUSIC`）を自動ミュートし、本編復帰時に元の音量へ戻します。
3. **真っ黒な省電力カバー（OLED向け）**
   - 画面全体を黒で覆い、中央に `広告ミュート中` ＋経過時間＋ライブ残り秒数＋大きな `強制解除（1タップ）` ボタンを表示します。
   - `FLAG_NOT_FOCUSABLE` により、カバー中も裏のNetflix広告再生が一時停止しません。
4. **フェイルセーフ＆実機UIノード診断モード（PC不要）**
   - 最大継続タイマー（デフォルト90秒）、1タップ強制解除、他アプリ切替・画面OFF時の即時復元、オンデバイスUIノード採取機能を搭載。

## PayPay両立モード 初回セットアップ（Windows + USB接続）

初回のみ PC と Android スマホを USB デバッグ接続し、プロジェクトルートで以下を実行します：

```powershell
powershell -ExecutionPolicy Bypass -File .\setup_paypay_safe.ps1
```

このスクリプトが以下を自動実行します：
1. `./gradlew.bat assembleDebug`
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. `adb shell pm grant com.antigravity.netflixadshield android.permission.WRITE_SECURE_SETTINGS`
4. `adb shell appops set com.antigravity.netflixadshield GET_USAGE_STATS allow`

完了後、スマホで **Netflix Ad Shield** を開き、
1. 「他のアプリの上に重ねて表示」を許可
2. 「0. PayPay両立モード」の権限表示がすべて緑（付与済み / 許可済み）であることを確認
3. **「自動切替（PayPay両立モード）を開始」** をタップ

※ 万一すぐユーザー補助を切りたい場合は、アプリ内の **「今すぐユーザー補助を強制OFFにする」** ボタンまたは通知バーの **「監視停止＆OFF」** から1タップで即座にOFFにできます。
