// Copyright (c) 2019 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "PaddleDetector.h"
#include "Pipeline.h"
#include <android/bitmap.h>
#include <unordered_map>

#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     cn_darrenyou_doctorcam_paddle_Native
 * Method:    nativeInit
 * Signature:
 * (Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;II[F[FF)J
 */
JNIEXPORT jlong JNICALL
Java_cn_darrenyou_doctorcam_paddle_PaddleDetector_nativeInit(
    JNIEnv *env, jclass thiz, jstring jModelDir, jstring jLabelPath,
    jint cpuThreadNum, jstring jCPUPowerMode, jint inputWidth, jint inputHeight,
    jfloatArray jInputMean, jfloatArray jInputStd, jfloat scoreThreshold) {
  std::string modelDir = jstring_to_cpp_string(env, jModelDir);
  std::string labelPath = jstring_to_cpp_string(env, jLabelPath);
  std::string cpuPowerMode = jstring_to_cpp_string(env, jCPUPowerMode);
  std::vector<float> inputMean = jfloatarray_to_float_vector(env, jInputMean);
  std::vector<float> inputStd = jfloatarray_to_float_vector(env, jInputStd);
  return reinterpret_cast<jlong>(
      new Pipeline(modelDir, labelPath, cpuThreadNum, cpuPowerMode, inputWidth,
                   inputHeight, inputMean, inputStd, scoreThreshold));
}

/*
 * Class:     cn_darrenyou_doctorcam_paddle_Native
 * Method:    nativeRelease
 * Signature: (J)Z
 */
JNIEXPORT jboolean JNICALL
Java_cn_darrenyou_doctorcam_paddle_PaddleDetector_nativeRelease(
    JNIEnv *env, jclass thiz, jlong ctx) {
  if (ctx == 0) {
    return JNI_FALSE;
  }
  Pipeline *pipeline = reinterpret_cast<Pipeline *>(ctx);
  delete pipeline;
  return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_cn_darrenyou_doctorcam_paddle_PaddleDetector_nativeGetRuntimeVersion(
    JNIEnv *env, jclass thiz, jlong ctx) {
  if (ctx == 0) {
    return nullptr;
  }
  const Pipeline *pipeline = reinterpret_cast<const Pipeline *>(ctx);
  const std::string version = pipeline->GetDetector()->GetRuntimeVersion();
  return env->NewStringUTF(version.c_str());
}

/*
 * Class:     cn_darrenyou_doctorcam_paddle_Native
 * Method:    nativeProcess
 * Signature: (JIIIILjava/lang/String;)Z
 */
JNIEXPORT jboolean JNICALL
Java_cn_darrenyou_doctorcam_paddle_PaddleDetector_nativeProcess(
    JNIEnv *env, jclass thiz, jlong ctx, jobject jARGB8888ImageBitmap,
    jstring jsavedImagePath) {
  if (ctx == 0) {
    return JNI_FALSE;
  }

  // Convert the android bitmap(ARGB8888) to the OpenCV RGBA image. Actually,
  // the data layout of AGRB8888 is R, G, B, A, it's the same as CV RGBA image,
  // so it is unnecessary to do the conversion of color format, check
  // https://developer.android.com/reference/android/graphics/Bitmap.Config#ARGB_8888
  // to get the more details about Bitmap.Config.ARGB8888
  auto t = GetCurrentTime();
  void *bitmapPixels;
  AndroidBitmapInfo bitmapInfo;
  if (AndroidBitmap_getInfo(env, jARGB8888ImageBitmap, &bitmapInfo) < 0) {
    LOGE("Invoke AndroidBitmap_getInfo() failed!");
    return JNI_FALSE;
  }
  if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    LOGE("Only Bitmap.Config.ARGB8888 color format is supported!");
    return JNI_FALSE;
  }
  if (AndroidBitmap_lockPixels(env, jARGB8888ImageBitmap, &bitmapPixels) < 0) {
    LOGE("Invoke AndroidBitmap_lockPixels() failed!");
    return JNI_FALSE;
  }
  cv::Mat bmpImage(bitmapInfo.height, bitmapInfo.width, CV_8UC4, bitmapPixels,
                   bitmapInfo.stride);
  cv::Mat rgbaImage;
  bmpImage.copyTo(rgbaImage);
  if (AndroidBitmap_unlockPixels(env, jARGB8888ImageBitmap) < 0) {
    LOGE("Invoke AndroidBitmap_unlockPixels() failed!");
    return JNI_FALSE;
  }
  LOGD("Read from bitmap costs %f ms", GetElapsedTime(t));

  std::string savedImagePath = jstring_to_cpp_string(env, jsavedImagePath);
  Pipeline *pipeline = reinterpret_cast<Pipeline *>(ctx);
  bool modified = pipeline->Process(rgbaImage, savedImagePath);
  if (modified) {
    // Convert the OpenCV RGBA image to the android bitmap(ARGB8888)
    if (rgbaImage.type() != CV_8UC4) {
      LOGE("Only CV_8UC4 color format is supported!");
      return JNI_FALSE;
    }
    t = GetCurrentTime();
    if (AndroidBitmap_lockPixels(env, jARGB8888ImageBitmap, &bitmapPixels) <
        0) {
      LOGE("Invoke AndroidBitmap_lockPixels() failed!");
      return JNI_FALSE;
    }
    cv::Mat bmpImage(bitmapInfo.height, bitmapInfo.width, CV_8UC4,
                     bitmapPixels, bitmapInfo.stride);
    rgbaImage.copyTo(bmpImage);
    if (AndroidBitmap_unlockPixels(env, jARGB8888ImageBitmap) < 0) {
      LOGE("Invoke AndroidBitmap_unlockPixels() failed!");
      return JNI_FALSE;
    }
    LOGD("Write to bitmap costs %f ms", GetElapsedTime(t));
  }

  return modified;
}

JNIEXPORT jobjectArray JNICALL
Java_cn_darrenyou_doctorcam_paddle_PaddleDetector_nativeProcessWithResults(
        JNIEnv *env, jclass thiz, jlong ctx, jobject jARGB8888ImageBitmap) {
    if (ctx == 0) {
        LOGE("Invalid context pointer");
        return nullptr;
    }

    // 获取Bitmap信息
    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, jARGB8888ImageBitmap, &bitmapInfo) < 0) {
        LOGE("Failed to get bitmap info");
        return nullptr;
    }
    if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format");
        return nullptr;
    }

    // 锁定像素
    void *bitmapPixels;
    if (AndroidBitmap_lockPixels(env, jARGB8888ImageBitmap, &bitmapPixels) < 0) {
        LOGE("Failed to lock pixels");
        return nullptr;
    }

    // Bitmap 像素只在锁定期间有效；复制后由 OpenCV 持有预测输入。
    cv::Mat bitmapImage(bitmapInfo.height, bitmapInfo.width, CV_8UC4,
                        bitmapPixels, bitmapInfo.stride);
    cv::Mat rgbaImage = bitmapImage.clone();
    if (AndroidBitmap_unlockPixels(env, jARGB8888ImageBitmap) < 0) {
        LOGE("Failed to unlock pixels");
        return nullptr;
    }

    // 获取Pipeline实例
    Pipeline *pipeline = reinterpret_cast<Pipeline *>(ctx);

    // 执行预测
    std::vector<Object> results;
    double preprocessTime, predictTime, postprocessTime;
    pipeline->GetDetector()->Predict(rgbaImage, &results, &preprocessTime, &predictTime, &postprocessTime);

    // 预加载标签列表到哈希表
    std::unordered_map<std::string, int> labelIndexMap;
    const auto& labelList = pipeline->GetDetector()->GetLabelList();
    for (size_t i = 0; i < labelList.size(); i++) {
        labelIndexMap[labelList[i]] = i;
    }

    // 创建结果数组
    jclass floatArrayClass = env->FindClass("[F");
    if (floatArrayClass == nullptr) {
        LOGE("Failed to find float array class");
        return nullptr;
    }

    jobjectArray resultArray = env->NewObjectArray(results.size(), floatArrayClass, nullptr);
    if (resultArray == nullptr) {
        LOGE("Failed to create result array");
        return nullptr;
    }

    jfloat itemData[6];

    // 填充结果
    for (size_t i = 0; i < results.size(); i++) {
        const Object& obj = results[i];

        // 使用哈希表快速查找标签索引
        int label_index = labelIndexMap.count(obj.class_name) ?
                          labelIndexMap[obj.class_name] : -1;

        itemData[0] = label_index;
        itemData[1] = obj.prob;
        itemData[2] = obj.x;
        itemData[3] = obj.y;
        itemData[4] = obj.w;
        itemData[5] = obj.h;

        jfloatArray item = env->NewFloatArray(6);
        if (item == nullptr) {
            LOGE("Failed to create float array");
            return nullptr;
        }

        env->SetFloatArrayRegion(item, 0, 6, itemData);
        env->SetObjectArrayElement(resultArray, i, item);
        env->DeleteLocalRef(item);
    }

    LOGD("Processed %zu objects in %.2f ms",
         results.size(), preprocessTime + predictTime + postprocessTime);
    return resultArray;
}

#ifdef __cplusplus
}
#endif
