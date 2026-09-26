# 🧠 Context Note for Antigravity IDE (Agent / AI)

> **⚠️ Superseded (2026-09-26) — read `AGENTS.md` first.** This note is kept for history only.
> Where it conflicts with `AGENTS.md`, `AGENTS.md` wins. In particular:
>
> - Artify is an **original** product. Do **not** extract, copy or derive any texture, icon, brush,
>   name or file from another app's package (steps 5–6 and the "Assets" item below are withdrawn).
> - The build has since been completed (see `walkthrough.md`), so "not compiled yet" is out of date.
> - Current plan and status: `docs/STATUS.md`, `docs/PRODUCT_ROADMAP.md`, `docs/specs/`.
> - Asset provenance work: `docs/specs/PHASE-0A2-asset-provenance.md`.
>
> **تنبيه:** هذه الملاحظة قديمة ومحفوظة للتاريخ فقط. المرجع هو `AGENTS.md`. لا تستخرج أو تنسخ أي
> خامات أو أيقونات أو فرش من تطبيقات أخرى.

## 📌 Project Overview (نظرة عامة على المشروع)
**Goal:** We are building a professional digital painting application for Android, heavily inspired by "Procreate" (iOS). 
**Original Reference:** We have the original `Procreate_5.3.6.ipa` file in this directory. We previously extracted it to study its structure, UI icons, and brush textures.
**Tech Stack:** Native Android, Kotlin, Custom `View` for Canvas drawing, XML for UI, Room Database for saving artworks.

## 🚀 What has been done so far (ما تم إنجازه حتى الآن)
1. **Project Structure:** Created a standard Android Gradle project.
2. **UI & Layouts (`res/layout`):** 
   - `activity_canvas.xml`: Floating panels over a full-screen canvas (matching Procreate's dark theme).
   - `activity_gallery.xml`: Artwork gallery view.
3. **Core Engine (`canvas/`):**
   - `DrawingView.kt`: Custom view handling touch events, drawing paths, and zoom/pan.
   - `BrushEngine.kt`: Handles brush size, opacity, and pressure logic.
   - `Layer.kt` & `CanvasViewModel.kt`: Layer management (add, delete, visibility) and undo/redo logic.
4. **Tools & Features:**
   - `ColorPickerPanel.kt`: Color wheel and RGB/Hex inputs.
   - `BrushLibrary.kt` & `BrushPanel.kt`: Categorized brushes.
   - Tools like Selection, Transform, and Export.
5. **Assets:** Injected some extracted Procreate icons (like the app icon) into the resources.

## 🚧 Current State & Issues (الحالة الحالية والمشاكل)
- The code is written, but it has **NOT** been compiled or built yet.
- The machine has Android SDK installed (API 36) at `C:\Users\ENG ALI\AppData\Local\Android\Sdk`. 
- Android Studio is **NOT** installed in the default paths, but Gradle wrapper is configured in this project.
- There may be missing imports or syntax errors that need to be fixed upon first compilation.

## 🎯 Next Steps for the AI Agent (ما يجب على الذكاء الاصطناعي فعله تالياً)
When the user asks to continue:
1. **Analyze the Code:** Check `build.gradle` and the Kotlin files in `app/src/main/java/...` to ensure all imports and dependencies are correct.
2. **Build the App:** Help the user build the app using `./gradlew assembleDebug`.
3. **Fix Errors:** If compilation fails (which is likely since the code was generated rapidly), fix the Kotlin/XML errors one by one.
4. **Run on Device/Emulator:** Assist the user in installing the APK on their Android phone or an emulator to test the drawing canvas.
5. **Implement Real Brushes:** The current `DrawingView.kt` uses basic Android `Canvas` and `Paint`. To reach Procreate's quality, we need to upgrade the drawing engine to use bitmap stamping (drawing a texture image repeatedly along the touch path) or migrate to OpenGL ES in the future.
6. **Use the IPA:** The `Procreate_5.3.6.ipa` is here if you need to extract more textures (using Python or unzip tools) to use as brush alpha shapes.

---
*End of context note. Agent, please read this carefully before assisting the user.*
