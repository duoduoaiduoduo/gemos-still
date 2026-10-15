package art.gemosdodo.still;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Exposes only the most recent memory through a temporary Android share grant. */
public final class MemoryProvider extends ContentProvider {
 static Uri uri(Context context){return Uri.parse("content://"+context.getPackageName()+".memories/latest.still");}
 @Override public boolean onCreate(){return true;}
 private File file(Uri uri)throws FileNotFoundException {
  if(!"/latest.still".equals(uri.getPath()))throw new FileNotFoundException();
  File file=new File(getContext().getFilesDir(),"last-memory.still");if(!file.isFile())throw new FileNotFoundException();return file;
 }
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {if(!"r".equals(mode))throw new FileNotFoundException("Read-only memory");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
 @Override public String getType(Uri uri){return "application/octet-stream";}
 @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
  String[] columns=projection!=null?projection:new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};MatrixCursor cursor=new MatrixCursor(columns);
  try{File file=file(uri);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]="Gemos-Still.still";else if(OpenableColumns.SIZE.equals(columns[i]))values[i]=file.length();}cursor.addRow(values);}catch(FileNotFoundException ignored){}return cursor;
 }
 @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
 @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
 @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
