# Far_Auto — Product Overview & User Guide

Welcome to Far_Auto! This guide will help you understand what Far_Auto is and how to use it to automate tasks on your Android phone, even if you are not a developer.

---

## 📱 What is Far_Auto?

Far_Auto is a powerful tool that lets you automate actions on your Android phone using simple Python scripts. 
Unlike other automation tools, Far_Auto:
- **Does NOT require Rooting** your phone (which can void your warranty).
- **Does NOT require a PC or ADB** (no cables needed).
- **Runs in the background** so it keeps working even when you turn off the screen!

You can use it to automate repetitive tasks, fill forms, test apps, or even let an AI Agent (like Claude or Cursor) control your phone for you!

### 💡 What can you do with Far_Auto?
Here are some common ways people use Far_Auto:
- **Game Macros:** Automate clicks or repetitive actions in games.
- **Auto-Filling Forms:** Quickly fill out long forms or login screens.
- **App Testing:** Test how your app behaves by scripting user flows.
- **AI Research:** Let an AI read content on your phone and summarize it for you.

---

## 🚀 How to Use Far_Auto (Non-Technical Guide)

### Step 1: Turn on the Engine (Accessibility Service)
To allow Far_Auto to perform clicks and read the screen for you, you need to enable its Accessibility Service.
1. Open the Far_Auto app on your phone.
2. You will see a banner saying "Accessibility Service Required".
3. Tap **Enable Service**.
4. This will open your phone's system settings. Find **Far_Auto** in the list and turn it ON.
   *(Note: On Android 13+, you might need to tap the 3 dots in the top right of the App Info screen and select "Allow restricted settings" first!)*

### Step 2: Open the Web Dashboard
You can manage and run scripts from a beautiful dashboard in your computer's web browser.
1. Make sure your phone and computer are on the **same Wi-Fi network**.
2. Look at the main screen of the Far_Auto app on your phone. It will show a URL (like `http://192.168.1.18:8080`) and a **Token**.
3. Open that URL in your computer's browser.
4. Enter the Token when prompted to unlock the console!

### Step 3: Run Your First Script
1. In the Web Dashboard, you will see a list of scripts.
2. Find `ui_explorer.py` (a built-in helper script) or any script you want to run.
3. Click the **Run** (▶) button next to it.
4. You can see what the script is doing in the live terminal console on the web page!
5. To stop a script, click **Stop Script** in the dashboard or double-press the volume down button on your phone.

---

## 🤖 How to Connect an AI Agent (Live Agent Mode)

Want an AI like Claude or Cursor to control your phone and perform tasks for you? Follow these simple steps!

1. Open the Far_Auto app on your phone.
2. Tap the **MCP** text in the top header.
3. Tap **SEND SETUP TO WEB CONSOLE**.
4. Now, look at the Web Dashboard on your computer. A popup will appear with the setup package!
5. Follow the steps on that popup:
   - **Step 1:** Run `pip install mcp httpx` in your computer's terminal.
   - **Step 2:** Copy the script displayed and save it as `far_auto_mcp.py` on your computer.
   - **Step 3:** Copy the JSON config and paste it into your Claude Desktop or Cursor settings. (The IP and Token are already filled in for you!).

Once connected, your AI can see the screen, tap buttons, and type text on your phone!

---

## 🛡️ Safety & Control
- **Instant Stop:** You can always stop any script by double-pressing the Volume Down button on your phone or clicking "Stop" in the notification.
- **Privacy:** Far_Auto does not send your data to any external servers. Everything runs locally on your device and your local Wi-Fi network.

---

## ❓ Common Questions & Troubleshooting

### Q: Why can't I open the Web Dashboard on my computer?
- **Check Wi-Fi:** Ensure both your phone and computer are on the **exact same Wi-Fi network**.
- **Check IP Address:** Make sure you typed the address exactly as shown on the phone screen (including the `:8080` part).
- **Firewall:** Sometimes your computer's firewall might block the connection. Try disabling it temporarily or allowing the connection.

### Q: The Accessibility Service turned off by itself!
- Android is very aggressive about saving battery. It sometimes stops background services.
- **Fix:** Go to your phone's Settings -> Apps -> Far_Auto -> Battery. Set it to **"Unrestricted"** so Android doesn't kill it.

### Q: Is it safe to give Far_Auto Accessibility permissions?
- **Yes.** Far_Auto only uses this permission to read the screen (so it knows where buttons are) and to perform clicks for your scripts. It **never** sends your data to the internet.
