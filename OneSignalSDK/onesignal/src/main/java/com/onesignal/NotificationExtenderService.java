/**
 * Modified MIT License
 *
 * Copyright 2016 OneSignal
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * 2. All copies of substantial portions of the Software may only be used in connection
 * with services provided by OneSignal.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.onesignal;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.core.app.NotificationCompat;

import org.json.JSONException;
import org.json.JSONObject;

import com.onesignal.OSNotificationProcessingManager.NotificationProcessingManager;

/**
 * OneSignal supports sending additional data along with a notification as key/value pairs.
 * You can read this additional data when a notification is opened by adding a
 * {@link com.onesignal.OneSignal.NotificationOpenedHandler} instead.
 * <br/><br/>
 * However, if you want to do one of the following, continue with the instructions
 * <a href="https://documentation.onesignal.com/docs/android-native-sdk#section--notificationextenderservice-">here</a>:
 * <br/><br/>
 * - Receive data in the background with our without displaying a notification
 * <br/>
 * - Override specific notification settings depending on client-side app logic (e.g. custom accent color,
 * vibration pattern, any other {@link NotificationCompat} options)
 */
public class NotificationExtenderService {

   // The extension service app AndroidManifest.xml meta data tag key name
   private static final String EXTENSION_SERVICE_META_DATA_TAG_NAME = "com.onesignal.NotificationExtensionServiceClass";

   private static NotificationExtenderService mService;

   public static NotificationExtenderService getInstance() {
      if (mService == null)
         mService = new NotificationExtenderService();

      return mService;
   }

   public static class OverrideSettings {
      public NotificationCompat.Extender extender;
      public Integer androidNotificationId;

      // Note: Make sure future fields are nullable.
      // Possible future options
      //    int badgeCount;
      //   NotificationCompat.Extender summaryExtender;

      void override(OverrideSettings overrideSettings) {
         if (overrideSettings == null)
            return;

         if (overrideSettings.androidNotificationId != null)
            androidNotificationId = overrideSettings.androidNotificationId;
      }
   }

   OSNotificationReceivedResult notificationReceivedResult;
   boolean isNotificationModified;
   JSONObject currentJsonPayload;
   boolean currentlyRestoring;
   Long restoreTimestamp;
   OverrideSettings currentBaseOverrideSettings = null;

   // Developer may call to override some notification settings.
   // If this method is called the SDK will omit it's notification regardless of what is returned from onNotificationProcessing.
   protected final OSNotificationReceivedResult displayNotification(Context context, OverrideSettings overrideSettings) {
      // Check if this method has been called already or if no override was set.
      if (notificationReceivedResult != null || overrideSettings == null)
         return null;

      isNotificationModified = true;

      overrideSettings.override(currentBaseOverrideSettings);
      notificationReceivedResult = new OSNotificationReceivedResult();

      OSNotificationGenerationJob notifJob = createNotifJobFromCurrent(context);
      notifJob.overrideSettings = overrideSettings;

      notificationReceivedResult.androidNotificationId = NotificationBundleProcessor.ProcessJobForDisplay(notifJob);

      return notificationReceivedResult;
   }

   void processIntent(Context context, Intent intent) {
      Bundle bundle = intent.getExtras();

      // Service maybe triggered without extras on some Android devices on boot.
      // https://github.com/OneSignal/OneSignal-Android-SDK/issues/99
      if (bundle == null) {
         OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "No extras sent to NotificationExtenderService in its Intent!\n" + intent);
         return;
      }

      String jsonStrPayload = bundle.getString("json_payload");
      if (jsonStrPayload == null) {
         OneSignal.Log(OneSignal.LOG_LEVEL.ERROR, "json_payload key is nonexistent from bundle passed to NotificationExtenderService: " + bundle);
         return;
      }

      try {
         currentJsonPayload = new JSONObject(jsonStrPayload);
         currentlyRestoring = bundle.getBoolean("restoring", false);
         if (bundle.containsKey("android_notif_id")) {
            currentBaseOverrideSettings = new OverrideSettings();
            currentBaseOverrideSettings.androidNotificationId = bundle.getInt("android_notif_id");
         }

         if (!currentlyRestoring && OneSignal.notValidOrDuplicated(context, currentJsonPayload))
            return;

         restoreTimestamp = bundle.getLong("timestamp");
         processJsonObjectWithWorkManager(context, currentJsonPayload, currentlyRestoring);
      } catch (JSONException e) {
         e.printStackTrace();
      }
   }

   void processJsonObjectWithWorkManager(Context context, JSONObject currentJsonPayload, boolean restoring) {
      OSNotificationReceived notificationReceived = new OSNotificationReceived(
              NotificationBundleProcessor.OSNotificationPayloadFrom(currentJsonPayload),
              restoring,
              OneSignal.isAppActive());

      NotificationProcessingManager.beginEnqueueingWork(context, notificationReceived);
   }

   OSNotificationGenerationJob createNotifJobFromCurrent(Context context) {
      OSNotificationGenerationJob notifJob = new OSNotificationGenerationJob(context);
      notifJob.isRestoring = currentlyRestoring;
      notifJob.jsonPayload = currentJsonPayload;
      notifJob.shownTimeStamp = restoreTimestamp;
      notifJob.overrideSettings = currentBaseOverrideSettings;

      return notifJob;
   }

   /**
    * In addition to using the setters to set all of the handlers you can also create your own implementation
    *  within a separate class and give your AndroidManifest.xml a special meta data tag
    * The meta data tag looks like this:
    *  <meta-data android:name="com.onesignal.NotificationExtensionServiceClass" android:value="com.company.ExtensionService" />
    * <br/><br/>
    * TODO: Comment about {@link OneSignal.NotificationProcessingHandler}
    * <br/><br/>
    * In the case of the {@link OneSignal.ExtNotificationWillShowInForegroundHandler} and the {@link OneSignal.AppNotificationWillShowInForegroundHandler}
    *  there are also setters for these handlers. So why create this new class and implement
    *  the same handlers, won't they just overwrite each other?
    * No, the idea here is to keep track of two separate handlers and keep them both
    *  100% optional. The extension handlers are set using the class implementations and the app
    *  handlers set through the setter methods.
    * The extension handlers will always be called first and then bubble to the app handlers
    * <br/><br/>
    * @see OneSignal.NotificationProcessingHandler
    * @see OneSignal#setNotificationProcessingHandler
    * @see OneSignal.ExtNotificationWillShowInForegroundHandler
    * @see OneSignal#setExtNotificationWillShowInForegroundHandler
    */
   static void setupNotificationExtensionServiceClass() {
      String className = OSUtils.getManifestMeta(OneSignal.appContext, EXTENSION_SERVICE_META_DATA_TAG_NAME);

      // No meta data containing extension service class name
      if (className == null) {
         OneSignal.onesignalLog(OneSignal.LOG_LEVEL.VERBOSE, "No class found, not setting up OSNotificationExtensionService");
         return;
      }

      OneSignal.onesignalLog(OneSignal.LOG_LEVEL.VERBOSE, "Found class: " + className + ", attempting to call constructor");
      // Pass an instance of the given class to set any overridden handlers
      try {
         Class<?> clazz = Class.forName(className);
         Object clazzInstance = clazz.newInstance();

         // Attempt to find an implementation for the NotificationProcessingHandler and then set it using package-private setter
         if (clazzInstance instanceof OneSignal.NotificationProcessingHandler) {
            OneSignal.setNotificationProcessingHandler((OneSignal.NotificationProcessingHandler) clazzInstance);
         }

         // Attempt to find an implementation for the ExtNotificationWillShowInForegroundHandler and then set it using package-private setter
         if (clazzInstance instanceof OneSignal.ExtNotificationWillShowInForegroundHandler) {
            OneSignal.setExtNotificationWillShowInForegroundHandler((OneSignal.ExtNotificationWillShowInForegroundHandler) clazzInstance);
         }
      } catch (IllegalAccessException e) {
         e.printStackTrace();
      } catch (InstantiationException e) {
         e.printStackTrace();
      } catch (ClassNotFoundException e) {
         e.printStackTrace();
      }
   }
}