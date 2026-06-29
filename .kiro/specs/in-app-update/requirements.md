# Requirements Document

## Introduction

This feature integrates Google Play's official In-App Update API
(`com.google.android.play:app-update`) into the Effective Browser Android app
(`me.thimmaiah.ebors`). When a newer version of the app is available on the
Play Store, the app prompts the user to update without leaving the app. The
update is delivered through Google Play.

The scope is intentionally minimal: a simple update check on app start/resume,
a non-disruptive (flexible) update flow by default, a snackbar prompt to
finish installation once the download completes, and graceful no-op behavior
on devices or builds where Play is unavailable (such as the sideloaded
`.debug` build). The integration is hooked from the single main entry point,
`MainActivity`, and must not break the existing build, tests, or runtime
behavior.

## Glossary

- **App**: The Effective Browser Android application, package `me.thimmaiah.ebors`.
- **Main_Activity**: The single main entry Activity (`MainActivity`) from which the update check is initiated.
- **Update_Manager**: The component wrapping Google Play's `AppUpdateManager`, responsible for querying update availability and starting/completing update flows.
- **Update_Info**: The result of an update-availability query (`AppUpdateInfo`), describing whether an update is available and which flows are allowed.
- **Flexible_Flow**: The Play In-App Update flow that downloads the update in the background while the user keeps using the App, then requires an explicit install step.
- **Immediate_Flow**: The Play In-App Update flow that presents a full-screen blocking update experience for critical updates.
- **Install_Listener**: The `InstallStateUpdatedListener` that reports download/install progress for a Flexible_Flow.
- **Completion_Prompt**: A Material Snackbar shown to the user inviting them to finish installing a downloaded update.
- **Play_Available**: The condition where the App was installed via Google Play and a compatible Play Store is present on the device.
- **Update_Session**: A single foreground lifetime of the Main_Activity during which update prompting state is tracked.

## Requirements

### Requirement 1: Add and configure the In-App Update dependency

**User Story:** As a developer, I want the Play In-App Update library added through the version catalog, so that the in-app update capability is available without breaking the existing build.

#### Acceptance Criteria

1. THE App SHALL declare the `com.google.android.play:app-update` dependency in the Gradle version catalog at `gradle/libs.versions.toml` under a single pinned version reference in `[versions]`.
2. THE App SHALL declare the `com.google.android.play:app-update-ktx` dependency in the Gradle version catalog, reusing the same version reference as `app-update`.
3. THE App module (`app/build.gradle.kts`) SHALL consume both the `app-update` and `app-update-ktx` catalog entries on its compile and runtime classpath.
4. WHEN the Gradle `assemble` task runs after the dependency is added, THE App SHALL complete the build successfully with no new compile errors.
5. IF dependency resolution or compilation fails after the dependency is added, THEN THE build SHALL surface the failure without altering existing source files.
6. WHEN the existing unit test suite runs after the dependency is added, THE App SHALL pass all tests that passed before the change.

### Requirement 2: Check for an available update

**User Story:** As a user, I want the app to detect when a newer version is available on the Play Store, so that I can stay up to date.

#### Acceptance Criteria

1. WHILE Play_Available, WHEN the Main_Activity starts, THE Update_Manager SHALL request Update_Info from Google Play.
2. WHILE Play_Available, WHEN the Main_Activity resumes, THE Update_Manager SHALL request Update_Info from Google Play.
3. WHEN Update_Info reports that an update is available and a Flexible_Flow is allowed, THE Update_Manager SHALL start a Flexible_Flow.
4. WHERE an Immediate_Flow is required for a critical update, WHEN Update_Info reports that an update is available, THE Update_Manager SHALL start an Immediate_Flow instead of a Flexible_Flow.
5. IF the Update_Info request fails or Play_Available is false, THEN THE Update_Manager SHALL allow the Main_Activity to continue without showing an update prompt.

### Requirement 3: Run the flexible update flow

**User Story:** As a user, I want updates to download in the background while I keep browsing, so that updating does not interrupt my session.

#### Acceptance Criteria

1. WHEN a Flexible_Flow is started, THE Update_Manager SHALL launch the Play update consent screen using an activity-result registration.
2. IF the user cancels or declines the Play update consent screen, THEN THE Update_Manager SHALL not start the download and SHALL leave the current browsing session uninterrupted.
3. WHILE a Flexible_Flow download is in progress, THE Update_Manager SHALL observe download progress through an Install_Listener.
4. WHEN an Install_Listener reports that the download has reached the DOWNLOADED state, THE Update_Manager SHALL display a Completion_Prompt as a Snackbar on the Main_Activity root view that remains visible until the user accepts or dismisses it.
5. IF an Install_Listener reports a FAILED download state, THEN THE Update_Manager SHALL not display a Completion_Prompt and SHALL leave the browsing session uninterrupted.
6. WHEN the user accepts the Completion_Prompt, THE Update_Manager SHALL invoke `completeUpdate` to install the downloaded update.

### Requirement 4: Resume an in-progress update

**User Story:** As a user, I want an update that was already downloading or downloaded to resume when I return to the app, so that I do not lose progress.

#### Acceptance Criteria

1. WHEN the Main_Activity resumes AND Update_Info reports that an update has already reached the DOWNLOADED state, THE Update_Manager SHALL display the Completion_Prompt prompting the user to complete installation.
2. WHEN the Main_Activity resumes AND Update_Info reports a developer-triggered Immediate_Flow in the DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS state, THE Update_Manager SHALL resume the Immediate_Flow.
3. IF the query for Update_Info fails when the Main_Activity resumes, THEN THE Update_Manager SHALL take no update action and leave the current screen unchanged.
4. WHEN the Main_Activity is destroyed, THE Update_Manager SHALL unregister the Install_Listener.

### Requirement 5: Handle unavailable Play environment

**User Story:** As a user on a non-Play build or device, I want the app to behave normally, so that the update feature never disrupts or crashes the app.

#### Acceptance Criteria

1. IF Play_Available is false, THEN THE Update_Manager SHALL complete the update check as a no-op without displaying any update prompt or error to the user and without interrupting the App.
2. IF Update_Info reports that no update is available, THEN THE Update_Manager SHALL take no further update action during the current request and SHALL not display any update prompt to the user.
3. IF the Update_Info request fails, THEN THE Update_Manager SHALL log the failure and SHALL allow the App to continue normal operation without displaying any error to the user.

### Requirement 6: Handle cancellation and flow failure

**User Story:** As a user, I want to be able to decline or cancel an update, so that I remain in control of the app.

#### Acceptance Criteria

1. IF the user cancels or dismisses the update consent screen, THEN THE Update_Manager SHALL allow the App to continue normal operation without blocking the user interface.
2. IF the user cancels or dismisses the update consent screen, THEN THE Update_Manager SHALL NOT trigger another update prompt within the current Update_Session.
3. IF the update flow returns any non-success result, THEN THE Update_Manager SHALL log the result, allow the App to continue normal operation without blocking the user interface, and not trigger another update prompt within the current Update_Session.

### Requirement 7: Avoid repeated prompting within a session

**User Story:** As a user, I want to avoid being prompted to update repeatedly, so that the experience is not annoying.

#### Acceptance Criteria

1. WHILE a single Update_Session is active, THE Update_Manager SHALL start at most one update flow for the same Update_Info, where two Update_Info values are considered the same when they reference the identical target version.
2. WHEN the user dismisses or cancels an update flow for an Update_Info within an Update_Session, THE Update_Manager SHALL suppress all further update prompts for that Update_Info until a new Update_Session begins.
3. WHEN the App enters the foreground after having been in the background, THE Update_Manager SHALL begin a new Update_Session.
