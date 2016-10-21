package xyz.klinker.messenger.view;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.support.v13.view.inputmethod.EditorInfoCompat;
import android.support.v13.view.inputmethod.InputConnectionCompat;
import android.support.v13.view.inputmethod.InputContentInfoCompat;
import android.support.v4.os.BuildCompat;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

import xyz.klinker.messenger.data.MimeType;

public class ImageKeyboardEditText extends EditText {

    private InputConnectionCompat.OnCommitContentListener commitContentListener;

    public ImageKeyboardEditText(Context context) {
        super(context);
    }

    public ImageKeyboardEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ImageKeyboardEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setCommitContentListener(InputConnectionCompat.OnCommitContentListener listener) {
        this.commitContentListener = listener;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        final InputConnection con = super.onCreateInputConnection(outAttrs);
        EditorInfoCompat.setContentMimeTypes(outAttrs, new String[] { "image/gif", "image/png" });

        return InputConnectionCompat.createWrapper(con, outAttrs, new InputConnectionCompat.OnCommitContentListener() {
            @Override
            public boolean onCommitContent(InputContentInfoCompat inputContentInfo, int flags, Bundle opts) {
                if (commitContentListener != null) {
                    if (BuildCompat.isAtLeastNMR1() &&
                            (flags & InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                        try {
                            inputContentInfo.requestPermission();
                        } catch (Exception e) {
                            return false;
                        }
                    }

                    commitContentListener.onCommitContent(
                            inputContentInfo, flags, opts
                    );

                    return true;
                } else {
                    return false;
                }
            }
        });
    }

}