import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class SafeScreenHome extends StatefulWidget {
  const SafeScreenHome({super.key});

  @override
  State<SafeScreenHome> createState() => _SafeScreenHomeState();
}

class _SafeScreenHomeState extends State<SafeScreenHome> {
  // تعريف قناة الاتصال البرمجية (MethodChannel)
  // تأكد أن هذا الاسم مطابق تماماً للاسم الموجود في ملف MainActivity.kt
  static const _channel = MethodChannel('com.ghadhoo_buler/screen_monitor');
  
  bool _isActive = false;

  @override
  void initState() {
    super.initState();
    // التحقق من حالة الخدمة فور فتح التطبيق
    _checkInitialStatus();
  }

  /// دالة للتحقق مما إذا كانت خدمة المراقبة تعمل حالياً في الخلفية
  Future<void> _checkInitialStatus() async {
    try {
      final bool status = await _channel.invokeMethod('isMonitoring');
      setState(() {
        _isActive = status;
      });
    } catch (e) {
      debugPrint("خطأ في جلب حالة الخدمة من النيتف: $e");
    }
  }

  /// الدالة المسؤولة عن تشغيل أو إيقاف الحماية
  Future<void> _toggleProtection() async {
    try {
      if (_isActive) {
        // طلب إيقاف الخدمة من كود كوتلن
        await _channel.invokeMethod('stopMonitoring');
      } else {
        // طلب بدء الخدمة (سيقوم أندرويد هنا بطلب إذن تصوير الشاشة)
        await _channel.invokeMethod('startMonitoring');
      }

      // إضافة تأخير بسيط لإعطاء فرصة للنظام لتغيير حالة الخدمة قبل إعادة الفحص
      await Future.delayed(const Duration(milliseconds: 600));
      await _checkInitialStatus();
      
    } on PlatformException catch (e) {
      debugPrint("خطأ في تنفيذ الأمر عبر MethodChannel: ${e.message}");
    }
  }

  @override
  Widget build(BuildContext context) {
    // تنسيق الألوان بناءً على الحالة
    final Color statusColor = _isActive ? Colors.green : Colors.red;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Ghadhoo - غدو'),
        centerTitle: true,
        elevation: 0,
      ),
      body: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // الجزء البصري للحالة (أيقونة متحركة)
            AnimatedContainer(
              duration: const Duration(milliseconds: 500),
              curve: Curves.easeInOut,
              padding: const EdgeInsets.all(30),
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: statusColor.withOpacity(0.1),
                border: Border.all(color: statusColor.withOpacity(0.3), width: 2),
              ),
              child: Icon(
                _isActive ? Icons.security : Icons.gpp_maybe,
                size: 120,
                color: statusColor,
              ),
            ),
            
            const SizedBox(height: 30),
            
            // نص الحالة
            Text(
              _isActive ? "الحماية نشطة الآن" : "الحماية متوقفة",
              style: TextStyle(
                fontSize: 26,
                fontWeight: FontWeight.bold,
                color: statusColor,
              ),
            ),
            
            const SizedBox(height: 15),
            
            // وصف بسيط للمستخدم
            const Text(
              'مشروع غضوٌ يقوم بتحليل محتوى الشاشة باستخدام الذكاء الاصطناعي المحلي لحجب المناظر غير اللائقة تلقائياً.',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 16,
                color: Colors.grey,
                height: 1.5,
              ),
            ),
            
            const SizedBox(height: 50),
            
            // زر التشغيل والإيقاف
            SizedBox(
              width: double.infinity,
              height: 60,
              child: ElevatedButton.icon(
                onPressed: _toggleProtection,
                icon: Icon(_isActive ? Icons.stop_circle : Icons.play_circle_filled),
                label: Text(
                  _isActive ? 'إيقاف نظام الحماية' : 'تفعيل نظام الحماية',
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                style: ElevatedButton.styleFrom(
                  backgroundColor: statusColor,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(15),
                  ),
                  elevation: 4,
                ),
              ),
            ),
            
            const SizedBox(height: 20),
            
            // تنبيه للمستخدم عن الخصوصية
            if (!_isActive)
              const Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.info_outline, size: 16, color: Colors.orange),
                  SizedBox(width: 5),
                  Text(
                    'سيطلب النظام إذن تسجيل الشاشة للبدء',
                    style: TextStyle(color: Colors.orange, fontSize: 13),
                  ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}