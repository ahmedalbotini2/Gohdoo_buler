import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:ui';

class SafeScreenHome extends StatefulWidget {
  const SafeScreenHome({super.key});

  @override
  State<SafeScreenHome> createState() => _SafeScreenHomeState();
}

class _SafeScreenHomeState extends State<SafeScreenHome>
    with TickerProviderStateMixin {
  static const _channel = MethodChannel('com.ghadhoo_buler/screen_monitor');

  bool _isActive = false;
  double _blurStrength = 20.0; // شدة الـ Blur الافتراضية
  late AnimationController _pulseController;
  late Animation<double> _pulseAnimation;

  @override
  void initState() {
    super.initState();
    _checkInitialStatus();

    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 2),
    )..repeat(reverse: true);

    _pulseAnimation = Tween<double>(begin: 1.0, end: 1.08).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );
  }

  @override
  void dispose() {
    _pulseController.dispose();
    super.dispose();
  }

  Future<void> _checkInitialStatus() async {
    try {
      final bool status = await _channel.invokeMethod('isMonitoring');
      setState(() => _isActive = status);
    } catch (e) {
      debugPrint("خطأ في جلب حالة الخدمة: $e");
    }
  }

  Future<void> _toggleProtection() async {
    try {
      if (_isActive) {
        await _channel.invokeMethod('stopMonitoring');
      } else {
        await _channel.invokeMethod('startMonitoring');
      }
      await Future.delayed(const Duration(milliseconds: 600));
      await _checkInitialStatus();
    } on PlatformException catch (e) {
      debugPrint("خطأ: ${e.message}");
    }
  }

  // إرسال قيمة الـ Blur إلى الـ Native عبر MethodChannel
  Future<void> _updateBlurStrength(double value) async {
    setState(() => _blurStrength = value);
    try {
      await _channel.invokeMethod('setBlurStrength', {'value': value});
    } catch (e) {
      debugPrint("خطأ في إرسال قيمة Blur: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    final bool isDark = Theme.of(context).brightness == Brightness.dark;

    return Scaffold(
      backgroundColor: isDark ? const Color(0xFF0A0A0F) : const Color(0xFFF0F4FF),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              _buildHeader(),
              const SizedBox(height: 32),
              _buildStatusCard(),
              const SizedBox(height: 24),
              _buildBlurPreviewCard(),
              const SizedBox(height: 24),
              _buildBlurSlider(),
              const SizedBox(height: 28),
              _buildToggleButton(),
              const SizedBox(height: 20),
              _buildFooterNote(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'غدو',
              style: TextStyle(
                fontSize: 32,
                fontWeight: FontWeight.w900,
                color: _isActive ? const Color(0xFF00E5A0) : const Color(0xFF6C7AE0),
                letterSpacing: -1,
              ),
            ),
            Text(
              'حماية ذكية للشاشة',
              style: TextStyle(
                fontSize: 13,
                color: Colors.grey.shade500,
                letterSpacing: 0.5,
              ),
            ),
          ],
        ),
        AnimatedBuilder(
          animation: _pulseAnimation,
          builder: (context, child) {
            return Transform.scale(
              scale: _isActive ? _pulseAnimation.value : 1.0,
              child: Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: _isActive
                      ? const Color(0xFF00E5A0).withOpacity(0.15)
                      : Colors.grey.withOpacity(0.1),
                  border: Border.all(
                    color: _isActive
                        ? const Color(0xFF00E5A0)
                        : Colors.grey.shade400,
                    width: 2,
                  ),
                ),
                child: Icon(
                  _isActive ? Icons.shield : Icons.shield_outlined,
                  color: _isActive ? const Color(0xFF00E5A0) : Colors.grey,
                  size: 24,
                ),
              ),
            );
          },
        ),
      ],
    );
  }

  Widget _buildStatusCard() {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 400),
      curve: Curves.easeInOut,
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        gradient: LinearGradient(
          colors: _isActive
              ? [const Color(0xFF00E5A0).withOpacity(0.15), const Color(0xFF00B4D8).withOpacity(0.1)]
              : [const Color(0xFFFF4D6D).withOpacity(0.12), const Color(0xFFFF6B35).withOpacity(0.08)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        border: Border.all(
          color: _isActive
              ? const Color(0xFF00E5A0).withOpacity(0.3)
              : const Color(0xFFFF4D6D).withOpacity(0.3),
        ),
      ),
      child: Row(
        children: [
          Icon(
            _isActive ? Icons.check_circle_rounded : Icons.cancel_rounded,
            color: _isActive ? const Color(0xFF00E5A0) : const Color(0xFFFF4D6D),
            size: 36,
          ),
          const SizedBox(width: 14),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                _isActive ? 'الحماية نشطة' : 'الحماية متوقفة',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                  color: _isActive ? const Color(0xFF00E5A0) : const Color(0xFFFF4D6D),
                ),
              ),
              Text(
                _isActive
                    ? 'يتم تحليل الشاشة بالذكاء الاصطناعي'
                    : 'اضغط لتفعيل نظام الحماية',
                style: TextStyle(fontSize: 12, color: Colors.grey.shade500),
              ),
            ],
          ),
        ],
      ),
    );
  }

  // بطاقة معاينة الـ Blur
  Widget _buildBlurPreviewCard() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'معاينة شدة التضبيب',
          style: TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.w700,
            color: Color(0xFF6C7AE0),
          ),
        ),
        const SizedBox(height: 10),
        ClipRRect(
          borderRadius: BorderRadius.circular(16),
          child: Stack(
            children: [
              // خلفية تمثل محتوى الشاشة
              Container(
                width: double.infinity,
                height: 160,
                decoration: const BoxDecoration(
                  gradient: LinearGradient(
                    colors: [Color(0xFF1a1a2e), Color(0xFF16213e), Color(0xFF0f3460)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                ),
                child: Stack(
                  children: [
                    // دوائر زخرفية تمثل المحتوى
                    Positioned(top: 20, left: 30, child: _colorCircle(60, const Color(0xFFE91E63))),
                    Positioned(top: 50, right: 40, child: _colorCircle(80, const Color(0xFF9C27B0))),
                    Positioned(bottom: 10, left: 80, child: _colorCircle(50, const Color(0xFF2196F3))),
                    Center(
                      child: Text(
                        'محتوى الشاشة',
                        style: TextStyle(color: Colors.white.withOpacity(0.3), fontSize: 14),
                      ),
                    ),
                  ],
                ),
              ),
              // طبقة الـ Blur الفعلية
              Positioned.fill(
                child: BackdropFilter(
                  filter: ImageFilter.blur(
                    sigmaX: _blurStrength * 0.5,
                    sigmaY: _blurStrength * 0.5,
                  ),
                  child: Container(color: Colors.black.withOpacity(0.1)),
                ),
              ),
              // شارة تبين شدة الـ Blur
              Positioned(
                bottom: 10,
                right: 10,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                  decoration: BoxDecoration(
                    color: Colors.black.withOpacity(0.6),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Text(
                    'شدة التضبيب: ${_blurStrength.toInt()}',
                    style: const TextStyle(color: Colors.white, fontSize: 11),
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _colorCircle(double size, Color color) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(shape: BoxShape.circle, color: color.withOpacity(0.6)),
    );
  }

  // Seekbar التحكم في شدة الـ Blur
  Widget _buildBlurSlider() {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        color: Colors.white.withOpacity(0.05),
        border: Border.all(color: Colors.white.withOpacity(0.08)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Row(
                children: [
                  Icon(Icons.blur_on_rounded, color: Color(0xFF6C7AE0), size: 20),
                  SizedBox(width: 8),
                  Text(
                    'شدة التضبيب',
                    style: TextStyle(fontWeight: FontWeight.w600, fontSize: 15),
                  ),
                ],
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                decoration: BoxDecoration(
                  color: const Color(0xFF6C7AE0).withOpacity(0.15),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Text(
                  '${_blurStrength.toInt()} / 25',
                  style: const TextStyle(
                    color: Color(0xFF6C7AE0),
                    fontWeight: FontWeight.bold,
                    fontSize: 13,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          SliderTheme(
            data: SliderThemeData(
              trackHeight: 6,
              activeTrackColor: const Color(0xFF6C7AE0),
              inactiveTrackColor: const Color(0xFF6C7AE0).withOpacity(0.2),
              thumbColor: Colors.white,
              overlayColor: const Color(0xFF6C7AE0).withOpacity(0.2),
              thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 10),
            ),
            child: Slider(
              value: _blurStrength,
              min: 1.0,
              max: 25.0,
              divisions: 24,
              onChanged: _updateBlurStrength,
            ),
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('خفيف', style: TextStyle(fontSize: 11, color: Colors.grey.shade500)),
              Text('متوسط', style: TextStyle(fontSize: 11, color: Colors.grey.shade500)),
              Text('قوي', style: TextStyle(fontSize: 11, color: Colors.grey.shade500)),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildToggleButton() {
    return SizedBox(
      width: double.infinity,
      height: 58,
      child: ElevatedButton.icon(
        onPressed: _toggleProtection,
        icon: Icon(_isActive ? Icons.stop_circle_outlined : Icons.play_circle_filled),
        label: Text(
          _isActive ? 'إيقاف نظام الحماية' : 'تفعيل نظام الحماية',
          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
        ),
        style: ElevatedButton.styleFrom(
          backgroundColor: _isActive ? const Color(0xFFFF4D6D) : const Color(0xFF6C7AE0),
          foregroundColor: Colors.white,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          elevation: 0,
        ),
      ),
    );
  }

  Widget _buildFooterNote() {
    if (_isActive) return const SizedBox.shrink();
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        const Icon(Icons.info_outline, size: 14, color: Colors.orange),
        const SizedBox(width: 6),
        Text(
          'سيطلب النظام إذن تسجيل الشاشة للبدء',
          style: TextStyle(color: Colors.orange.shade400, fontSize: 12),
        ),
      ],
    );
  }
}