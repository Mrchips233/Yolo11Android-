package com.Chips.yolo11ncnn;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;


import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class FileUtils  {
    public static String getpath(Context context, Uri uri){
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q){
            String[] projection = {MediaStore.Video.Media.DATA};
            try(Cursor cursor = context.getContentResolver().query(uri,projection,null,null,null)){
                if(cursor != null && cursor.moveToFirst()){
                    int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
                    return cursor.getString(columnIndex);
                }
            }catch (Exception e){
                e.printStackTrace();
            }
        }
        return copyUriToInternalFile(context,uri);
    }

    private static String copyUriToInternalFile(Context context, Uri uri){
        try{
            ContentResolver resolver = context.getContentResolver();
            String extension = getExtension(resolver, uri);
            String fileName = "Video_Temp_" + System.currentTimeMillis() + (extension != null? "."+ extension :".mp4");
            File outFile = new File(context.getFilesDir(),fileName);

            try(InputStream in = resolver.openInputStream(uri);
                OutputStream out = new FileOutputStream(outFile)){
                byte[] buffer = new byte[4096];
                int length;
                if (in != null) {
                    while((length = in.read(buffer)) > 0){
                        out.write(buffer, 0, length);
                    }
                }
                out.flush();
            }
            return outFile.getAbsolutePath();
        }catch (Exception e){
            e.printStackTrace();
            return null;
        }
    }

    private static String getExtension(ContentResolver resolver , Uri uri){
        String mimeType = resolver.getType(uri);
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
    }
}
