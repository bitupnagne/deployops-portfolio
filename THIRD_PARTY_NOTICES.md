# Third-party notices

These notices apply to the named third-party resources. They do not grant a license to the application as a whole.

| Resource | Source and license |
| --- | --- |
| Pretendard Variable, including the renamed OTP UI font | [Official Pretendard](https://github.com/orioncactus/pretendard), SIL Open Font License 1.1. Complete original notice: [Pretendard-OFL.txt](src/main/resources/META-INF/licenses/Pretendard-OFL.txt). The font data is unchanged. |
| Material Icons artwork | [Google Material Design Icons](https://github.com/google/material-design-icons), Apache License 2.0. Existing notice: [google-material-icons-LICENSE.txt](src/main/resources/META-INF/licenses/google-material-icons-LICENSE.txt). |
| Maven Wrapper scripts | Original Apache Maven Wrapper notices remain in `mvnw` and `mvnw.cmd`. The downloaded wrapper JAR is not distributed here. |

The two OTP download QR images encode the official Google Authenticator store links: [Google Play](https://play.google.com/store/apps/details?id=com.google.android.apps.authenticator2) and [Apple App Store](https://apps.apple.com/kr/app/google-authenticator/id388497605). They do not encode an account, secret or enrollment key.

Java dependencies are declared in `pom.xml` and fetched from their package repositories at build time. Their licenses and notices remain those of their respective publishers; dependency JARs and a built application bundle are not part of this source repository.
