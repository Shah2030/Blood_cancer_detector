package com.example.bloodcancerdetector;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {

    private Interpreter tflite;
    private final int IMAGE_SIZE = 224;
    private DataType inputDataType;

    private final String[] labels = { "Cancer","Healthy","Unknown Image"};
    private Bitmap selectedBitmap;
    private Uri imageUri;

    private ImageView imageView;
    private TextView resultText;
    private Button galleryBtn, cameraBtn, pdfBtn, shareBtn;

    private String classificationResult = "";


    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 100);
            }
        }
        imageView = findViewById(R.id.imageView);
        resultText = findViewById(R.id.resultText);
        galleryBtn = findViewById(R.id.galleryBtn);
        cameraBtn = findViewById(R.id.captureBtn);
        pdfBtn = findViewById(R.id.savePdfBtn);
        shareBtn = findViewById(R.id.shareBtn);
        progressBar = findViewById(R.id.progressBar);

        // Disable buttons during loading
        setButtonsEnabled(false);

        // Load model in background thread
        new Thread(() -> {
            try {
                tflite = new Interpreter(loadModelFile());
                Tensor inputTensor = tflite.getInputTensor(0);
                inputDataType = inputTensor.dataType();
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    setButtonsEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    resultText.setText("❌ Model loading failed.");
                });
            }
        }).start();

        galleryBtn.setOnClickListener(v -> pickImageFromGallery());
        cameraBtn.setOnClickListener(v -> captureImageFromCamera());
        pdfBtn.setOnClickListener(v -> saveResultToPdf());
        shareBtn.setOnClickListener(v -> shareResult());
    }

    private void setButtonsEnabled(boolean enabled) {
        galleryBtn.setEnabled(enabled);
        cameraBtn.setEnabled(enabled);
        pdfBtn.setEnabled(enabled);
        shareBtn.setEnabled(enabled);
    }


    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = getAssets().openFd("blood_model.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY,
                fileDescriptor.getStartOffset(),
                fileDescriptor.getDeclaredLength());
    }

    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, 1);
    }

    private void captureImageFromCamera() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File photoFile = createImageFile();
        if (photoFile != null) {
            imageUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
            startActivityForResult(intent, 2);
        }
    }

    private File createImageFile() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        try {
            return File.createTempFile("IMG_" + timestamp + "_", ".jpg", storageDir);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        try {
            if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
                imageUri = data.getData();
                selectedBitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            } else if (requestCode == 2 && resultCode == RESULT_OK) {
                selectedBitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
            }

            if (selectedBitmap != null) {
                imageView.setImageBitmap(selectedBitmap);
                Bitmap resized = Bitmap.createScaledBitmap(selectedBitmap, IMAGE_SIZE, IMAGE_SIZE, true);
                classifyImage(resized);
            }
        } catch (IOException e) {
            resultText.setText("⚠️ Error loading image.");
            e.printStackTrace();
        }
    }

    private void classifyImage(Bitmap bitmap) {
        float[][][][] input = new float[1][IMAGE_SIZE][IMAGE_SIZE][3];
        int[] pixels = new int[IMAGE_SIZE * IMAGE_SIZE];
        bitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE);

        for (int i = 0; i < IMAGE_SIZE; i++) {
            for (int j = 0; j < IMAGE_SIZE; j++) {
                int pixel = pixels[i * IMAGE_SIZE + j];
                input[0][i][j][0] = ((pixel >> 16) & 0xFF) / 255.0f;
                input[0][i][j][1] = ((pixel >> 8) & 0xFF) / 255.0f;
                input[0][i][j][2] = (pixel & 0xFF) / 255.0f;
            }
        }

        float[][] output = new float[1][labels.length];
        tflite.run(input, output);

        int maxIdx = 0;
        for (int i = 1; i < output[0].length; i++) {
            if (output[0][i] > output[0][maxIdx]) maxIdx = i;
        }

        float confidence = output[0][maxIdx] * 100;
        classificationResult = "🧬 Type: " + labels[maxIdx] + "\n📊 Confidence: " + String.format("%.2f", confidence) + "%";
        resultText.setText(classificationResult);
    }

    private void saveResultToPdf() {
        if (classificationResult.isEmpty()) {
            Toast.makeText(this, "⚠️ Classify an image first.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            PdfDocument document = new PdfDocument();
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create(); // A4 size
            PdfDocument.Page page = document.startPage(pageInfo);

            Canvas canvas = page.getCanvas();
            Paint titlePaint = new Paint();
            titlePaint.setTextSize(25);
            titlePaint.setFakeBoldText(true);
            titlePaint.setColor(Color.rgb(50, 50, 150));

            Paint contentPaint = new Paint();
            contentPaint.setTextSize(16);
            contentPaint.setColor(Color.BLACK);

            // Title
            canvas.drawText("🧬 Blood Cancer Detection Report", 80, 60, titlePaint);

            // Date and time
            String dateTime = new SimpleDateFormat("dd MMM yyyy, hh:mm a").format(new Date());
            canvas.drawText("Generated on: " + dateTime, 50, 100, contentPaint);

            // Classification Result
            canvas.drawText("Classification Result:", 50, 140, contentPaint);
            canvas.drawText(classificationResult, 70, 170, contentPaint);

            // Add line separator
            canvas.drawLine(50, 200, 545, 200, contentPaint);

            // Add image with border
            if (selectedBitmap != null) {
                Bitmap scaled = Bitmap.createScaledBitmap(selectedBitmap, 400, 400, true);
                canvas.drawBitmap(scaled, 100, 220, null);
                //canvas.drawRect(98, 218, 502, 622, contentPaint); // border
            }

            // Footer
            canvas.drawText("© 2025 Blood Cancer Detector. All rights reserved", 100, 780, contentPaint);
            canvas.drawText("Powered by Android ML", 200, 800, contentPaint);
            document.finishPage(page);

            File file = new File(getExternalFilesDir(null), "report_" + System.currentTimeMillis() + ".pdf");
            FileOutputStream fos = new FileOutputStream(file);
            document.writeTo(fos);
            document.close();
            fos.close();

            Toast.makeText(this, "✅ PDF saved: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "❌ PDF generation failed.", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }


    private void shareResult() {
        if (classificationResult.isEmpty()) {
            Toast.makeText(this, "⚠️ Classify an image first.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // Generate PDF first
            PdfDocument document = new PdfDocument();
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create(); // A4 size
            PdfDocument.Page page = document.startPage(pageInfo);

            Canvas canvas = page.getCanvas();
            Paint titlePaint = new Paint();
            titlePaint.setTextSize(25);
            titlePaint.setFakeBoldText(true);
            titlePaint.setColor(Color.rgb(50, 50, 150));

            Paint contentPaint = new Paint();
            contentPaint.setTextSize(16);
            contentPaint.setColor(Color.BLACK);

            // Title
            canvas.drawText("🧬 Blood Cancer Detection Report", 80, 60, titlePaint);

            // Date and time
            String dateTime = new SimpleDateFormat("dd MMM yyyy, hh:mm a").format(new Date());
            canvas.drawText("Generated on: " + dateTime, 50, 100, contentPaint);

            // Classification Result
            canvas.drawText("Classification Result:", 50, 140, contentPaint);
            canvas.drawText(classificationResult, 70, 170, contentPaint);

            // Line
            canvas.drawLine(50, 200, 545, 200, contentPaint);

            // Image
            if (selectedBitmap != null) {
                Bitmap scaled = Bitmap.createScaledBitmap(selectedBitmap, 400, 400, true);
                canvas.drawBitmap(scaled, 100, 220, null);
            }

            // Footer
            canvas.drawText("© 2025 Blood Cancer Detector. All rights reserved", 100, 780, contentPaint);
            canvas.drawText("Powered by Android ML", 200, 800, contentPaint);
            document.finishPage(page);

            File file = new File(getExternalFilesDir(null), "shared_report.pdf");
            FileOutputStream fos = new FileOutputStream(file);
            document.writeTo(fos);
            document.close();
            fos.close();

            Uri pdfUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/pdf");
            shareIntent.putExtra(Intent.EXTRA_STREAM, pdfUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share PDF via"));

        } catch (IOException e) {
            Toast.makeText(this, "❌ Error sharing PDF.", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

}
