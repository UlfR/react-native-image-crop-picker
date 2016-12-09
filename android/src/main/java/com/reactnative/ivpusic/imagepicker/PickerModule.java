package com.reactnative.ivpusic.imagepicker;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.FileProvider;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.ArrayAdapter;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.PermissionAwareActivity;
import com.facebook.react.modules.core.PermissionListener;
import com.yalantis.ucrop.UCrop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Callable;

class PickerModule extends ReactContextBaseJavaModule implements ActivityEventListener {

    private static final int IMAGE_PICKER_REQUEST = 61110;
    private static final int CAMERA_PICKER_REQUEST = 61111;
    private static final String E_ACTIVITY_DOES_NOT_EXIST = "E_ACTIVITY_DOES_NOT_EXIST";

    private static final String E_PICKER_CANCELLED_KEY = "E_PICKER_CANCELLED";
    private static final String E_PICKER_CANCELLED_MSG = "User cancelled image selection";

    private static final String E_CALLBACK_ERROR = "E_CALLBACK_ERROR";
    private static final String E_FAILED_TO_SHOW_PICKER = "E_FAILED_TO_SHOW_PICKER";
    private static final String E_FAILED_TO_OPEN_CAMERA = "E_FAILED_TO_OPEN_CAMERA";
    private static final String E_NO_IMAGE_DATA_FOUND = "E_NO_IMAGE_DATA_FOUND";
    private static final String E_CAMERA_IS_NOT_AVAILABLE = "E_CAMERA_IS_NOT_AVAILABLE";
    private static final String E_CANNOT_LAUNCH_CAMERA = "E_CANNOT_LAUNCH_CAMERA";
    private static final String E_PERMISSIONS_MISSING = "E_PERMISSIONS_MISSING";
    private static final String E_ERROR_WHILE_CLEANING_FILES = "E_ERROR_WHILE_CLEANING_FILES";

    private Promise mPickerPromise;

    private String pickType;
    private boolean pickVideo = false;

    private boolean cropping = false;
    private boolean multiple = false;
    private boolean includeBase64 = false;

    //Default colors from from https://material.google.com/style/color.html#

    //Grey 800
    private final String DEFAULT_TINT = "#424242";
    private String cropperTintColor = DEFAULT_TINT;

    //Light Blue 500
    private final String DEFAULT_WIDGET_COLOR = "#03A9F4";
    private int width = 200;
    private int height = 200;
    private final ReactApplicationContext mReactContext;
    private Uri mCameraCaptureURI;
    private String mCurrentPhotoPath;

    PickerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        reactContext.addActivityEventListener(this);
        mReactContext = reactContext;
    }

    private String getTmpDir() {
        String tmpDir = mReactContext.getCacheDir() + "/react-native-image-crop-picker";
        Boolean created = new File(tmpDir).mkdir();

        return tmpDir;
    }

    @Override
    public String getName() {
        return "ImageCropPicker";
    }

    private void setConfiguration(final ReadableMap options) {
        multiple = options.hasKey("multiple") && options.getBoolean("multiple");
        includeBase64 = options.hasKey("includeBase64") && options.getBoolean("includeBase64");
        width = options.hasKey("width") ? options.getInt("width") : width;
        height = options.hasKey("height") ? options.getInt("height") : height;
        cropping = options.hasKey("cropping") ? options.getBoolean("cropping") : cropping;
        cropperTintColor = options.hasKey("cropperTintColor") ? options.getString("cropperTintColor") : cropperTintColor;

        pickVideo = false;
        if (options.hasKey("mediaType") && options.getString("mediaType").equals("video")) {
            pickVideo = true;
            cropping = false;
        } else {
            pickVideo = false;
        }
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }

        fileOrDirectory.delete();
    }

    @ReactMethod
    public void clean(final Promise promise) {

        final Activity activity = getCurrentActivity();
        final PickerModule module = this;

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        permissionsCheck(activity, promise, Arrays.asList(Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try {
                    File file = new File(module.getTmpDir());
                    if (!file.exists()) throw new Exception("File does not exist");

                    module.deleteRecursive(file);
                    promise.resolve(null);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    promise.reject(E_ERROR_WHILE_CLEANING_FILES, ex.getMessage());
                }

                return null;
            }
        });
    }

    @ReactMethod
    public void cleanSingle(final String pathToDelete, final Promise promise) {
        if (pathToDelete == null) {
            promise.reject(E_ERROR_WHILE_CLEANING_FILES, "Cannot cleanup empty path");
            return;
        }

        final Activity activity = getCurrentActivity();
        final PickerModule module = this;

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        permissionsCheck(activity, promise, Arrays.asList(Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try {
                    String path = pathToDelete;
                    final String filePrefix = "file://";
                    if (path.startsWith(filePrefix)) {
                        path = path.substring(filePrefix.length());
                    }

                    File file = new File(path);
                    if (!file.exists()) throw new Exception("File does not exist. Path: " + path);

                    module.deleteRecursive(file);
                    promise.resolve(null);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    promise.reject(E_ERROR_WHILE_CLEANING_FILES, ex.getMessage());
                }

                return null;
            }
        });
    }

    private void permissionsCheck(final Activity activity, final Promise promise, final List<String> requiredPermissions, final Callable<Void> callback) {

        List<String> missingPermissions = new ArrayList<>();

        for (String permission : requiredPermissions) {
            int status = ActivityCompat.checkSelfPermission(activity, permission);
            if (status != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (!missingPermissions.isEmpty()) {

            ((PermissionAwareActivity) activity).requestPermissions(missingPermissions.toArray(new String[missingPermissions.size()]), 1, new PermissionListener() {

                @Override
                public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
                    if (requestCode == 1) {

                        for (int grantResult : grantResults) {
                            if (grantResult == PackageManager.PERMISSION_DENIED) {
                                promise.reject(E_PERMISSIONS_MISSING, "Required permission missing");
                                return true;
                            }
                        }

                        try {
                            callback.call();
                        } catch (Exception e) {
                            promise.reject(E_CALLBACK_ERROR, "Unknown error", e);
                        }
                    }

                    return true;
                }
            });

            return;
        }

        // all permissions granted
        try {
            callback.call();
        } catch (Exception e) {
            promise.reject(E_CALLBACK_ERROR, "Unknown error", e);
        }
    }

    @ReactMethod
    public void openCamera(final ReadableMap options, final Promise promise) {

        if (!isCameraAvailable()) {
            promise.reject(E_CAMERA_IS_NOT_AVAILABLE, "Camera not available");
            return;
        }

        final Activity activity = getCurrentActivity();

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        setConfiguration(options);
        mPickerPromise = promise;

        permissionsCheck(activity, promise, Arrays.asList(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                initiateCamera(activity);
                return null;
            }
        });
    }

    private void initiateCamera(Activity activity) {

        try {
            int requestCode = CAMERA_PICKER_REQUEST;
            Intent cameraIntent;// = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (pickVideo) {
                cameraIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            } else {
                cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            }

            File imageFile = createImageFile();

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                mCameraCaptureURI = Uri.fromFile(imageFile);
            } else {
                mCameraCaptureURI = FileProvider.getUriForFile(activity,
                        activity.getApplicationContext().getPackageName() + ".provider",
                        imageFile);
            }

            android.util.Log.v("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ", "set IMAGE FILENAME: " + mCameraCaptureURI);
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraCaptureURI);

            if (cameraIntent.resolveActivity(mReactContext.getPackageManager()) == null) {
                mPickerPromise.reject(E_CANNOT_LAUNCH_CAMERA, "Cannot launch camera");
                return;
            }

            activity.startActivityForResult(cameraIntent, requestCode);
        } catch (Exception e) {
            mPickerPromise.reject(E_FAILED_TO_OPEN_CAMERA, e);
        }

    }

    private void initiatePicker(final Activity activity) {
        try {
            final Intent galleryIntent = new Intent(Intent.ACTION_PICK);

            if (cropping) {
                galleryIntent.setType("image/*");
            } else {
                if (pickVideo) {
                    galleryIntent.setType("video/*");
                } else {
                    galleryIntent.setType("image/*");
                }
            }

            galleryIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
            galleryIntent.setAction(Intent.ACTION_GET_CONTENT);
            galleryIntent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);

            final Intent chooserIntent = Intent.createChooser(galleryIntent, "Pick an image");
            activity.startActivityForResult(chooserIntent, IMAGE_PICKER_REQUEST);
        } catch (Exception e) {
            mPickerPromise.reject(E_FAILED_TO_SHOW_PICKER, e);
        }
    }

    @ReactMethod
    public void openPicker(final ReadableMap options, final Promise promise) {
        final Activity activity = getCurrentActivity();

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        setConfiguration(options);
        mPickerPromise = promise;

        permissionsCheck(activity, promise, Arrays.asList(Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                initiatePicker(activity);
                return null;
            }
        });
    }

    @ReactMethod
    public void open(final ReadableMap options, final Promise promise) {
        Activity activity = getCurrentActivity();

        if (activity == null) {
            promise.reject(E_ACTIVITY_DOES_NOT_EXIST, "Activity doesn't exist");
            return;
        }

        final List<String> titles = new ArrayList<String>();
        final List<String> actions = new ArrayList<String>();

        if (options.hasKey("takePhotoButtonTitle")
                && options.getString("takePhotoButtonTitle") != null
                && !options.getString("takePhotoButtonTitle").isEmpty()) {
            titles.add(options.getString("takePhotoButtonTitle"));
            actions.add("photo");
        }
        if (options.hasKey("chooseFromLibraryButtonTitle")
                && options.getString("chooseFromLibraryButtonTitle") != null
                && !options.getString("chooseFromLibraryButtonTitle").isEmpty()) {
            titles.add(options.getString("chooseFromLibraryButtonTitle"));
            actions.add("library");
        }

        String cancelButtonTitle = options.getString("cancelButtonTitle");
        titles.add(cancelButtonTitle);
        actions.add("cancel");

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity,
                android.R.layout.select_dialog_item, titles);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        if (options.hasKey("title") && options.getString("title") != null && !options.getString("title").isEmpty()) {
            builder.setTitle(options.getString("title"));
        }

        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int index) {
                String action = actions.get(index);

                switch (action) {
                    case "photo":
                        pickType = "camera";
                        openCamera(options, promise);
                        break;
                    case "library":
                        pickType = "gallery";
                        openPicker(options, promise);
                        break;
                    case "cancel":
                        promise.reject("Cancel pressed", "");
                        break;
                }
            }
        });

        final AlertDialog dialog = builder.create();
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                dialog.dismiss();
                promise.reject("Cancel pressed", "");
            }
        });
        dialog.show();
    }

    private String getBase64StringFromFile(String absoluteFilePath) {
        InputStream inputStream;

        try {
            inputStream = new FileInputStream(new File(absoluteFilePath));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }

        byte[] bytes;
        byte[] buffer = new byte[8192];
        int bytesRead;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        bytes = output.toByteArray();
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }

        return type;
    }

    private WritableMap getSelection(Activity activity, Uri uri, boolean isCamera) throws Exception {
        String path = resolveRealPath(activity, uri, isCamera);
        if (path == null || path.isEmpty()) {
            throw new Exception("Cannot resolve asset path.");
        }

        String mime = getMimeType(path);
        android.util.Log.v("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ", "path: " + path);
        android.util.Log.v("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ", "mime: " + mime);
        if (pickVideo && mime != null && mime.startsWith("video/")) {
            return getVideo(path, mime);
        }

        return getImage(path);
    }

    private WritableMap getVideo(String path, String mime) {
        WritableMap image = new WritableNativeMap();

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(path);
        Bitmap bmp = retriever.getFrameAtTime();

        if (bmp != null) {
            image.putInt("width", bmp.getWidth());
            image.putInt("height", bmp.getHeight());
        }

        Bitmap thumbB = android.media.ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MICRO_KIND); // MINI_KIND or MICRO_KIND
        String thumbPath = this.getTmpDir() + "/image-" + UUID.randomUUID().toString() + ".jpeg";

        try {
            final java.io.FileOutputStream fos = new java.io.FileOutputStream(thumbPath);
            thumbB.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        String thumb = getBase64StringFromFile(thumbPath);
        File fileThumb = new File(thumbPath);
        fileThumb.delete();
        image.putString("thumb", thumb);

        image.putString("path", "file://" + path);
        image.putString("mime", mime);
        image.putInt("size", (int) new File(path).length());

        return image;
    }

    private String resolveRealPath(Activity activity, Uri uri, boolean isCamera) {
        String path;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            path = RealPathUtil.getRealPathFromURI(activity, uri);
        } else {
            if (isCamera) {
                Uri imageUri = Uri.parse(mCurrentPhotoPath);
                path = imageUri.getPath();
            } else {
                path = RealPathUtil.getRealPathFromURI(activity, uri);
            }
        }

        return path;
    }

    private WritableMap getImage(String path) throws Exception {
        WritableMap image = new WritableNativeMap();

        if (path.startsWith("http://") || path.startsWith("https://")) {
            throw new Exception("Cannot select remote files");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;

        BitmapFactory.decodeFile(path, options);

        if (options.outMimeType == null || options.outWidth == 0 || options.outHeight == 0) {
            throw new Exception("Invalid image selected");
        }

        image.putString("pickType", pickType);
        image.putString("path", "file://" + path);
        image.putInt("width", options.outWidth);
        image.putInt("height", options.outHeight);
        image.putString("mime", options.outMimeType);
        image.putInt("size", (int) new File(path).length());

        if (includeBase64) {
            image.putString("data", getBase64StringFromFile(path));
        }

        return image;
    }

    private void configureCropperColors(UCrop.Options options) {
        int color = Color.parseColor(cropperTintColor);
        options.setToolbarColor(color);
        options.setStatusBarColor(color);
        if (cropperTintColor.equals(DEFAULT_TINT)) {
            /*
            Default tint is grey => use a more flashy color that stands out more as the call to action
            Here we use 'Light Blue 500' from https://material.google.com/style/color.html#color-color-palette
            */
            options.setActiveWidgetColor(Color.parseColor(DEFAULT_WIDGET_COLOR));
        } else {
            //If they pass a custom tint color in, we use this for everything
            options.setActiveWidgetColor(color);
        }

    }

    private void startCropping(Activity activity, Uri uri) {
        UCrop.Options options = new UCrop.Options();
        options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
        configureCropperColors(options);

        UCrop.of(uri, Uri.fromFile(new File(this.getTmpDir(), UUID.randomUUID().toString() + ".jpg")))
                .withMaxResultSize(width, height)
                .withAspectRatio(width, height)
                .withOptions(options)
                .start(activity);
    }

    private void imagePickerResult(Activity activity, final int requestCode, final int resultCode, final Intent data) {
        if (mPickerPromise == null) {
            return;
        }

        if (resultCode == Activity.RESULT_CANCELED) {
            mPickerPromise.reject(E_PICKER_CANCELLED_KEY, E_PICKER_CANCELLED_MSG);
        } else if (resultCode == Activity.RESULT_OK) {
            if (multiple) {
                ClipData clipData = data.getClipData();
                WritableArray result = new WritableNativeArray();

                try {
                    // only one image selected
                    if (clipData == null) {
                        result.pushMap(getSelection(activity, data.getData(), false));
                    } else {
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            result.pushMap(getSelection(activity, clipData.getItemAt(i).getUri(), false));
                        }
                    }

                    mPickerPromise.resolve(result);
                } catch (Exception ex) {
                    mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, ex.getMessage());
                }

            } else {
                Uri uri = data.getData();

                if (uri == null) {
                    mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, "Cannot resolve image url");
                }

                if (cropping) {
                    startCropping(activity, uri);
                } else {
                    try {
                        mPickerPromise.resolve(getSelection(activity, uri, false));
                    } catch (Exception ex) {
                        mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, ex.getMessage());
                    }
                }
            }
        }
    }

    private void cameraPickerResult(Activity activity, final int requestCode, final int resultCode, final Intent data) {
        if (mPickerPromise == null) {
            return;
        }

        if (resultCode == Activity.RESULT_CANCELED) {
            mPickerPromise.reject(E_PICKER_CANCELLED_KEY, E_PICKER_CANCELLED_MSG);
        } else if (resultCode == Activity.RESULT_OK) {
            Uri uri = mCameraCaptureURI;

            if (uri == null) {
                mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, "Cannot resolve image url");
                return;
            }

            if (cropping) {
                UCrop.Options options = new UCrop.Options();
                options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
                startCropping(activity, uri);
            } else {
                try {
                    Uri vUri = data.getData();
                    android.util.Log.v("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ", "vUri: " + vUri.toString());
                    android.util.Log.v("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ", "cameraPickerResult: (vUri)" + vUri);
                    android.util.Log.v("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ", "uri: " + uri.getPath());

                    mPickerPromise.resolve(getSelection(activity, uri, true));
                } catch (Exception ex) {
                    mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, ex.getMessage());
                }
            }
        }
    }

    private void croppingResult(Activity activity, final int requestCode, final int resultCode, final Intent data) {
        if (mPickerPromise == null) {
            return;
        }

        if (data != null) {
            final Uri resultUri = UCrop.getOutput(data);
            if (resultUri != null) {
                try {
                    mPickerPromise.resolve(getSelection(activity, resultUri, false));
                } catch (Exception ex) {
                    mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, ex.getMessage());
                }
            } else {
                mPickerPromise.reject(E_NO_IMAGE_DATA_FOUND, "Cannot find image data");
            }
        } else {
            mPickerPromise.reject(E_PICKER_CANCELLED_KEY, E_PICKER_CANCELLED_MSG);
        }
    }

    @Override
    public void onActivityResult(Activity activity, final int requestCode, final int resultCode, final Intent data) {
        if (requestCode == IMAGE_PICKER_REQUEST) {
            imagePickerResult(activity, requestCode, resultCode, data);
        } else if (requestCode == CAMERA_PICKER_REQUEST) {
            cameraPickerResult(activity, requestCode, resultCode, data);
        } else if (requestCode == UCrop.REQUEST_CROP) {
            croppingResult(activity, requestCode, resultCode, data);
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
    }

    private boolean isCameraAvailable() {
        return mReactContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)
                || mReactContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    private File createImageFile() throws IOException {
        String imageFileName = "image-" + UUID.randomUUID().toString();
        File path = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(imageFileName, (pickVideo ? ".mp4" : ".jpg"), path);

        // Save a file: path for use with ACTION_VIEW intents
        mCurrentPhotoPath = "file:" + image.getAbsolutePath();

        return image;
    }
}
