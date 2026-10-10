#!/usr/bin/env bash
# shellcheck shell=bash

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_NAME_ANDROID_APP="doctor-cam-android-app"
APP_NAME="doctor-cam"
ANDROID_APP_MODULE_NAME="app"
ANDROID_GRADLEW_PATH="./gradlew"
ANDROID_BUILD_CHANNELS="official"
ANDROID_DEFAULT_CHANNEL="official"
ANDROID_DEFAULT_ARTIFACT="apk"
ANDROID_RELEASE_VARIANT="release"

# 当前 Job 的构建宿主为 mac-ci-1，使用该宿主已安装的固定 JDK 与 SDK。
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="${HOME}/Library/Android/sdk"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
[[ -x "${JAVA_HOME}/bin/java" && -d "${ANDROID_HOME}" ]] || {
  printf '%s\n' '[ERROR] DoctorCam 正式构建缺少固定 JDK 21 或 Android SDK' >&2
  return 1
}
export PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

# 只读取本次受控快照的产品签名 Profile，不消费外部文件 override。
# shellcheck disable=SC1091
source "${PROJECT_ROOT}/fast-deploy-core/shared/load_workspace_profile_env.sh"
doctor_cam_signing_profile="$(resolve_workspace_shared_profile_env_file doctor-cam/android-app/shared.prod.env)" || {
  printf '%s\n' '[ERROR] DoctorCam 固定签名 Profile 不可用' >&2
  return 1
}
unset DOCTOR_CAM_ANDROID_SIGNING_KEYSTORE_BASE64 DOCTOR_CAM_ANDROID_SIGNING_PASSWORD
unset DOCTOR_CAM_ANDROID_SIGNING_KEY_ALIAS DOCTOR_CAM_ANDROID_SIGNING_CERT_SHA256
set -a
# shellcheck disable=SC1090
source "${doctor_cam_signing_profile}"
set +a
unset doctor_cam_signing_profile

for doctor_cam_signing_field in \
  DOCTOR_CAM_ANDROID_SIGNING_KEYSTORE_BASE64 \
  DOCTOR_CAM_ANDROID_SIGNING_PASSWORD \
  DOCTOR_CAM_ANDROID_SIGNING_KEY_ALIAS \
  DOCTOR_CAM_ANDROID_SIGNING_CERT_SHA256; do
  [[ -n "${!doctor_cam_signing_field:-}" ]] || {
    printf '[ERROR] DoctorCam 签名字段缺失: %s\n' "${doctor_cam_signing_field}" >&2
    return 1
  }
done
unset doctor_cam_signing_field

RELEASE_DEBUG_CHECK_HOOK="check_release_debug_disabled"
check_release_debug_disabled() (
  cd "${PROJECT_ROOT}"
  "${ANDROID_GRADLEW_PATH}" :app:verifyReleaseDebugDisabled --no-daemon -Pandroid.builder.sdkDownload=false
)
