package com.transcale.abajiang

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = getSharedPreferences("TranslatorAppPrefs", MODE_PRIVATE)

        // 读取存储的背景图片 URI
        val savedBackgroundUri = sharedPreferences.getString("background_uri", null)
        val initialBitmap = savedBackgroundUri?.let { uriString ->
            val uri = Uri.parse(uriString)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(this.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(this.contentResolver, uri)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load bitmap from URI: $uriString, error: ${e.message}")
                null
            }
        }

        setContent {
            TranslatorScreen(this, initialBitmap)
        }
    }
}

@Composable
fun TranslatorScreen(context: Context, initialBitmap: Bitmap?) {
    var inputText by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var sourceLang by remember { mutableStateOf("zh") }
    var targetLang by remember { mutableStateOf("en") }
    var backgroundBitmap by remember { mutableStateOf(initialBitmap) }
    val languages = listOf("中文 (zh)", "英语 (en)", "法语 (fr)", "德语 (de)", "西班牙语 (es)")
    val languageCodes = listOf("zh", "en", "fr", "de", "es")

    // 相册选择器
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            // 持久化 URI 访问权限
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to take persistable URI permission: ${e.message}")
            }

            val bitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load bitmap: ${e.message}")
                null
            }

            if (bitmap != null) {
                backgroundBitmap = bitmap
                // 存储 URI 到 SharedPreferences
                val editor = context.getSharedPreferences("TranslatorAppPrefs", Context.MODE_PRIVATE).edit()
                editor.putString("background_uri", uri.toString())
                editor.apply()
                Log.d("MainActivity", "Background URI saved: $uri")
            }
        }
    }

    // 权限请求器
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            launcher.launch("image/*")
        } else {
            Log.d("MainActivity", "Permission denied")
            // 权限被拒绝，可以显示提示
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (backgroundBitmap == null) Color(0xFFD3D3D3) else Color.Transparent
            )
    ) {
        // 背景图片
        backgroundBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "背景图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // 主内容
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .align(Alignment.Center),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LanguageTag(
                    text = "源语言 ($sourceLang)",
                    languages = languages,
                    languageCodes = languageCodes,
                    onLanguageSelected = { code -> sourceLang = code }
                )
                LanguageTag(
                    text = "目标语言 ($targetLang)",
                    languages = languages,
                    languageCodes = languageCodes,
                    onLanguageSelected = { code -> targetLang = code }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InputBox(
                    text = inputText,
                    onValueChange = { inputText = it },
                    placeholder = "输入翻译的内容"
                )
                ResultBox(resultText)
            }

            Button(
                onClick = {
                    if (inputText.isNotEmpty()) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val result = translateText(inputText, sourceLang, targetLang)
                            resultText = result
                        }
                    } else {
                        resultText = "请输入翻译的内容"
                    }
                },
                modifier = Modifier
                    .width(120.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFA67BFF),
                    contentColor = Color.White
                )
            ) {
                Text("翻译", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 更换背景和还原背景按钮
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        android.Manifest.permission.READ_MEDIA_IMAGES
                    } else {
                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                        launcher.launch("image/*")
                    } else {
                        permissionLauncher.launch(permission)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA67BFF))
            ) {
                Text("更换背景", color = Color.White)
            }

            Button(
                onClick = {
                    backgroundBitmap = null
                    // 清除存储的背景 URI
                    val editor = context.getSharedPreferences("TranslatorAppPrefs", Context.MODE_PRIVATE).edit()
                    editor.remove("background_uri")
                    editor.apply()
                    Log.d("MainActivity", "Background URI cleared")
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA67BFF))
            ) {
                Text("还原背景", color = Color.White)
            }
        }
    }
}

@Composable
fun LanguageTag(
    text: String,
    languages: List<String>,
    languageCodes: List<String>,
    onLanguageSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Text(
            text = text,
            color = Color.White,
            modifier = Modifier
                .background(Color(0xFFA67BFF), RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFFA67BFF))
        ) {
            languages.forEachIndexed { index, language ->
                DropdownMenuItem(
                    text = { Text(language, color = Color.White) },
                    onClick = {
                        onLanguageSelected(languageCodes[index])
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun InputBox(text: String, onValueChange: (String) -> Unit, placeholder: String) {
    TextField(
        value = text,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Color.Gray) },
        modifier = Modifier
            .size(150.dp, 150.dp)
            .background(Color(0xFFA67BFF), RoundedCornerShape(16.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFFA67BFF),
            unfocusedContainerColor = Color(0xFFA67BFF),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp)
    )
}

@Composable
fun ResultBox(result: String) {
    Box(
        modifier = Modifier
            .size(150.dp, 150.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
    ) {
        Text(
            text = result,
            color = Color.Black,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(8.dp),
            fontSize = 16.sp
        )
    }
}

private suspend fun translateText(text: String, sourceLang: String, targetLang: String): String {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val url = "https://api.mymemory.translated.net/get?q=$text&langpair=$sourceLang|$targetLang"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val json = response.body?.string() ?: return@withContext "翻译失败：响应为空"
            val jsonObject = JSONObject(json)
            val translatedText = jsonObject.getJSONObject("responseData").getString("translatedText")
                ?: return@withContext "翻译失败：无翻译结果"
            translatedText
        } catch (e: Exception) {
            "翻译失败：${e.message}"
        }
    }
}