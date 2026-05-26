// import 'dart:typed_data';
// import 'package:flutter/services.dart';
// import 'package:image/image.dart' as img;
// import 'package:safety_screen/resourse.dart';
// import 'package:tflite_flutter/tflite_flutter.dart';

// class SafeScreenController {
//   // القناة المشتركة مع كوتلن
//   static const MethodChannel _channel = MethodChannel('com.ghadhoo_buler/screen_monitor');
  
//   Interpreter? _interpreter;
//   bool _isModelLoaded = false;

//   // دالة التهيئة (تحميل النموذج وإعداد المستمع)
//   Future<void> initialize() async {
//     await loadModel();
//     _setupNativeListener();
//   }

//   // 1. تحميل نموذج الذكاء الاصطناعي
//   Future<void> loadModel() async {
//     try {
//       // تأكد من أن الاسم يطابق ملف الـ assets
//       _interpreter = await Interpreter.fromAsset(modles.tf_modle);
//       _isModelLoaded = true;
//       print("AI Model loaded successfully!");
//     } catch (e) {
//       print("Error loading TFLite model: $e");
//     }
//   }

//   // 2. إعداد الجسر لاستقبال الصور من كوتلن
//   void _setupNativeListener() {
//     _channel.setMethodCallHandler((call) async {
//       if (call.method == "analyzeFrameFromNative") {
//         final Map arguments = call.arguments as Map;
//         final Uint8List frameBytes = arguments['bytes'];
//         final int width = arguments['width'];
//         final int height = arguments['height'];

//         return await _processImageWithLocalAI(frameBytes, width, height);
//       }
//       return false;
//     });
//   }

//   // 3. تحليل الصورة (المنطق الرياضي للنموذج)
//   Future<bool> _processImageWithLocalAI(Uint8List bytes, int width, int height) async {
//     if (!_isModelLoaded || _interpreter == null) return false;

//     try {
//       final rawImage = img.Image.fromBytes(
//         width: width,
//         height: height,
//         bytes: bytes.buffer,
//         order: img.ChannelOrder.rgba,
//       );

//       final resizedImage = img.copyResize(rawImage, width: 224, height: 224);

//       // تجهيز مصفوفة المدخلات (Normalization)
//       var input = List.generate(1, (i) => List.generate(224, (j) => List.generate(224, (k) => List.filled(3, 0.0))));
//       for (int y = 0; y < 224; y++) {
//         for (int x = 0; x < 224; x++) {
//           final pixel = resizedImage.getPixel(x, y);
//           input[0][y][x][0] = pixel.r / 255.0;
//           input[0][y][x][1] = pixel.g / 255.0;
//           input[0][y][x][2] = pixel.b / 255.0;
//         }
//       }

//       // مصفوفة المخرجات (لنموذج يحتوي على 5 فئات كما في NSFWAnalyzer)
//       var output = List.filled(1 * 5, 0.0).reshape([1, 5]);
//       _interpreter!.run(input, output);

//       // الفئات: [Drawings, Hentai, Neutral, Porn, Sexy]
//       double hentaiProb = output[0][1];
//       double pornProb = output[0][3];
//       double sexyProb = output[0][4];

//       // قرار الحجب
//       return pornProb > 0.6 || hentaiProb > 0.6 || sexyProb > 0.7;
//     } catch (e) {
//       print("AI Analysis error: $e");
//       return false;
//     }
//   }

//   // دوال التحكم في الخدمة (التحدث مع نيتف)
//   Future<bool> checkMonitoringStatus() async {
//     return await _channel.invokeMethod('isMonitoring') ?? false;
//   }

//   Future<void> startMonitoring() async {
//     await _channel.invokeMethod('startMonitoring');
//   }

//   Future<void> stopMonitoring() async {
//     await _channel.invokeMethod('stopMonitoring');
//   }

//   void dispose() {
//     _interpreter?.close();
//   }
// }