# MONICA Java SDK

Java 11 以上向けの MONICA SDK。Maven reactor は次の公開 artifact を検証する。

- `com.accelhack.monica:monica-core`
- `com.accelhack.monica:monica-logback`
- `com.accelhack.monica:monica-spring-boot2-starter`
- `com.accelhack.monica:monica-android`

```bash
mvn verify
```

Spring Boot 2 starter の DSN は secret key を含むため、設定ファイルへ実値を保存せず
`MONICA_DSN` などの環境変数から渡す。

`monica-android` は配布物へ埋め込まれるので、逆に public key（`mpk_`）しか受け付けない。
Android SDK は AAR ではなく素の JAR として、この reactor から一緒に出る。

## release

開発中のPOMは`X.Y.Z-SNAPSHOT`にする。4個のrepository secret
`MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_TOKEN`、`MAVEN_GPG_PRIVATE_KEY`、
`MAVEN_GPG_PASSPHRASE`を設定し、Central Portalで`com.accelhack.monica` namespaceを
検証した後、対応するmain commitへ`maven-vX.Y.Z` tagを付ける。

`maven-release.yml`はtagとPOM versionの対応を検証し、release versionへ一時変換してから、
source / Javadoc jarとGPG signatureを含む4 artifactをMaven Centralへ公開する。
