import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:safety_screen/res/data_color.dart';
import 'dart:ui' show ImageFilter;
import 'package:safety_screen/res/resourse.dart';
import 'package:safety_screen/themes/gold_ornament.dart';
import 'package:safety_screen/themes/islamic_background.dart';
import 'package:safety_screen/widgets/islamic_card.dart';
import 'package:safety_screen/widgets/mode_card.dart';


class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _SafeScreenHomeState();
}

class _SafeScreenHomeState extends State<HomeScreen>
    with TickerProviderStateMixin {
  static const _channel = MethodChannel('com.ghadhoo_buler/screen_monitor');

  bool _isActive       = false;
  int  _androidVersion = 0;
  Color _selectedColor = const Color(0xFF0A0A0A);
  double _blurRadius   = 15.0;


  bool _useLocalAi = true;

  late AnimationController _pulseController;
  late AnimationController _rotateController;
  late Animation<double>   _pulseAnim;

  static  List<ColorOption> _colorOptions = [
    ColorOption('أسود',       Color(0xFF0A0A0A)),
    ColorOption('أخضر ', Color(0xFF1A3C2A)),
    ColorOption('أزرق ', Color(0xFF0D1B2A)),
    ColorOption('بنفسجي',     Color(0xFF1E1030)),
    ColorOption('رمادي',      Color(0xFF1A1A1A)),
    ColorOption('بني ',    Color(0xFF2C1500)),
  ];

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this, duration: const Duration(seconds: 2),
    )..repeat(reverse: true);
    _rotateController = AnimationController(
      vsync: this, duration: const Duration(seconds: 30),
    )..repeat();
    _pulseAnim = Tween<double>(begin: 0.95, end: 1.05).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );
    _loadAndroidVersion();
    _checkInitialStatus();
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _rotateController.dispose();
    super.dispose();
  }

  Future<void> _loadAndroidVersion() async {
    try {
      final int v = await _channel.invokeMethod('getAndroidVersion');
      setState(() => _androidVersion = v);
    } catch (_) {}
  }

  Future<void> _checkInitialStatus() async {
    try {
      final bool status = await _channel.invokeMethod('isMonitoring');
      setState(() => _isActive = status);
    } catch (e) {
      debugPrint("خطأ: $e");
    }
  }

  Future<void> _toggleProtection() async {
    try {
      if (_isActive) {
        await _channel.invokeMethod('stopMonitoring');
      } else {
        await _channel.invokeMethod('startMonitoring');
        await _pushCurrentSettings();
      }
      await Future.delayed(const Duration(milliseconds: 600));
      await _checkInitialStatus();
    } on PlatformException catch (e) {
      debugPrint("PlatformException: ${e.message}");
    }
  }

  Future<void> _pushCurrentSettings() async {
    if (_androidVersion >= 31) {
      await _channel.invokeMethod('setBlurRadius', {'radius': _blurRadius});
    } else {
      await _channel.invokeMethod('setOverlayColor', {
        'color': _selectedColor.toARGB32(),
      });
    }
  }

  String get _blurLabel {
    if (_blurRadius <= 6)  return 'خفيف';
    if (_blurRadius <= 13) return 'متوسط';
    if (_blurRadius <= 20) return 'قوي';
    return 'أقصى';
  }

  // ── ألوان الثيم الإسلامي ────────────────────────────────────────────────────
  static const Color _gold      = Color(0xFFB8960C);
  static const Color _goldDim   = Color(0xFF8A6A00);
  static const Color _goldLight = Color(0xFFD4AF37);
  static const Color _bg        = Color(0xFF0C0E0B);
  static const Color _surface   = Color(0xFF141810);
  static const Color _surface2  = Color(0xFF1C2118);
  static const Color _textMain  = Color(0xFFEDE8D0);
  static const Color _textSub   = Color(0xFF8A8670);

  @override
  Widget build(BuildContext context) {
    return Directionality(
      textDirection: TextDirection.rtl,
      child: Scaffold(
        backgroundColor: _bg,
        body: Stack(
          children: [
            // ── خلفية زخرفية دوّارة ──────────────────────────────────────────
            Positioned.fill(child: IslamicBackground(controller: _rotateController)),

            // ── المحتوى ───────────────────────────────────────────────────────
            SafeArea(
              child: Column(
                children: [
                  _buildAppBar(),
                  Expanded(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.symmetric(horizontal: 20),
                      child: Column(
                        children: [
                          const SizedBox(height: 24),
                          _buildStatusWidget(),
                          const SizedBox(height: 28),
                          _buildAyah(),
                          const SizedBox(height: 24),
                          _buildCustomizationPanel(),
                          const SizedBox(height: 24),
                          _buildToggleButton(),
                          const SizedBox(height: 32),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ── AppBar ─────────────────────────────────────────────────────────────────
  Widget _buildAppBar() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
      decoration: BoxDecoration(
        color: _surface.withValues(alpha: 0.95),
        border: Border(
          bottom: BorderSide(color: _gold.withValues(alpha: 0.3), width: 1),
        ),
      ),
      child: Row(
        children: [
          // زخرفة يسار
          GoldOrnament(size: 22),
          const Spacer(),
          // العنوان
          Column(
            children: [
              Text(
                'غُضُّوا',
                style: TextStyle(
                  fontFamily: 'serif',
                  fontSize: 26,
                  fontWeight: FontWeight.bold,
                  color: _goldLight,
                  letterSpacing: 2,
                  shadows: [Shadow(color: _gold.withValues(alpha: 0.5), blurRadius: 12)],
                ),
              ),
              Text(
                ' تطبيق مساعد على غض البصر',
                style: TextStyle(
                  fontSize: 11,
                  color: _textSub,
                  letterSpacing: 3,
                ),
              ),
            ],
          ),
          const Spacer(),
          // زخرفة يمين
          GoldOrnament(size: 22, mirror: true),
        ],
      ),
    );
  }

  // ── ويدجت الحالة ──────────────────────────────────────────────────────────
  Widget _buildStatusWidget() {
    final bool active = _isActive;
    return AnimatedBuilder(
      animation: _pulseAnim,
      builder: (context, child) {
        return Transform.scale(
          scale: active ? _pulseAnim.value : 1.0,
          child: child,
        );
      },
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(vertical: 28, horizontal: 20),
        decoration: BoxDecoration(
          color: _surface,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: active
                ? _gold.withValues(alpha: 0.6)
                : _textSub.withValues(alpha: 0.2),
            width: 1.5,
          ),
          boxShadow: active
              ? [BoxShadow(color: _gold.withValues(alpha: 0.15), blurRadius: 24, spreadRadius: 2)]
              : [],
        ),
        child: Column(
          children: [
            // أيقونة العين
            Stack(
              alignment: Alignment.center,
              children: [
                if (active)
                  Container(
                    width: 90, height: 90,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _gold.withValues(alpha: 0.08),
                    ),
                  ),
                Container(
                  width: 72, height: 72,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: active
                        ? _gold.withValues(alpha: 0.15)
                        : _surface2,
                    border: Border.all(
                      color: active ? _gold : _textSub.withValues(alpha: 0.3),
                      width: 1.5,
                    ),
                  ),
                  child: Icon(
                    active ? Icons.visibility : Icons.visibility_off,
                    color: active ? _goldLight : _textSub,
                    size: 32,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Text(
              active ? 'الحماية نشطة' : 'الحماية متوقفة',
              style: TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.bold,
                color: active ? _goldLight : _textSub,
                fontFamily: 'serif',
              ),
            ),
            const SizedBox(height: 6),
            Text(
              active
                  ? 'يتم تحليل الشاشة بالذكاء الاصطناعي المحلي'
                  : 'اضغط لتفعيل نظام الحماية',
              style: TextStyle(fontSize: 12, color: _textSub),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }

  // ──  مفتاح التبديل بين الوضع الاغتراضي و الوضع الاحترافي ──────────────────────────────────────────────────────
  Widget _buildAyah() {
  
    final bool isLocal = _useLocalAi;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
      decoration: BoxDecoration(
        color: _surface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: _gold.withValues(alpha: 0.2), width: 1),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ── العنوان ──────────────────────────────────────────────────────
          Row(
            children: [
              Icon(Icons.memory_outlined, color: _gold, size: 16),
              const SizedBox(width: 8),
              Text(
                'وضع المحلل',
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: _textMain),
              ),
              const Spacer(),
              // شارة "قريباً" لوضع API
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: _goldDim.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: _goldDim.withValues(alpha: 0.3)),
                ),
                child: Text(
                  ' قريباً',
                  style: TextStyle(fontSize: 10, color: _goldDim, fontWeight: FontWeight.w600),
                ),
              ),
            ],
          ),

          const SizedBox(height: 16),

          // ── بطاقتا الاختيار ───────────────────────────────────────────
          Row(
            children: [
              // ── محلي ──────────────────────────────────────────────────
              Expanded(
                child: ModeCard(
                  selected:  isLocal,
                  enabled:   true,
                  icon:      Icons.smartphone_outlined,
                  title:     'محلي',
                  subtitle:  'خصوصية كاملة',
                  label: "",
                  onTap:     () => setState(() => _useLocalAi = true),
                ),
              ),
              const SizedBox(width: 10),
              // ── API ────────────────────────────────────────────────────
              Expanded(
                child: ModeCard(
                  selected:  !isLocal,
                  enabled:   false,   // ← معطّل حالياً
                  icon:      Icons.cloud_outlined,
                  title:     'احترافي',
                  subtitle:  'دقة أعلى',
                  label: '',
                  onTap:     null,    // لا شيء عند الضغط
                ),
              ),
            ],
          ),
        
        ],
      ),
    );
  }

  Widget goldDivider() => Expanded(
    child: Container(height: 1, color: _gold.withValues(alpha: 0.25)),
  );

  // ── لوحة التخصيص ──────────────────────────────────────────────────────────
  Widget _buildCustomizationPanel() {
    if (_androidVersion >= 31) {
      // Android 12+: شدة الـ blur
      return IslamicCard(
        title: 'شدة الضبابية',
        subtitle: _blurLabel,
        icon: Icons.blur_on_outlined,
        child: Column(
          children: [
            const SizedBox(height: 12),
            SliderTheme(
              data: SliderTheme.of(context).copyWith(
                activeTrackColor:   _gold,
                inactiveTrackColor: _gold.withValues(alpha: 0.15),
                thumbColor:         _goldLight,
                overlayColor:       _gold.withValues(alpha: 0.1),
                trackHeight:        3,
              ),
              child: Slider(
                value:     _blurRadius,
                min:       1.0,
                max:       25.0,
                divisions: 24,
                label:     _blurRadius.toInt().toString(),
                onChanged:    (v) => setState(() => _blurRadius = v),
                onChangeEnd:  (_) => _pushCurrentSettings(),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('خفيف', style: TextStyle(fontSize: 11, color: _textSub)),
                  Text('متوسط', style: TextStyle(fontSize: 11, color: _textSub)),
                  Text('أقصى',  style: TextStyle(fontSize: 11, color: _textSub)),
                ],
              ),
            ),
            const SizedBox(height: 14),
            // ── معاينة الـ blur على الصورة الحقيقية ──────────────────────────
            ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: Stack(
                alignment: Alignment.center,
                children: [
                  // الصورة الأصلية
                  Image(
                    image: const AssetImage(ImageAppResources.blurTest),
                    height: 100,
                    width: double.infinity,
                    fit: BoxFit.cover,
                  ),
                  // طبقة ضبابية محاكاة بـ BackdropFilter
                  Positioned.fill(
                    child: BackdropFilter(
                      filter: ImageFilter.blur(
                        sigmaX: _blurRadius * 0.6,
                        sigmaY: _blurRadius * 0.6,
                      ),
                      child: Container(color: Colors.transparent),
                    ),
                  ),
                  // نص معاينة
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                    decoration: BoxDecoration(
                      color: Colors.black45,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      'معاينة الضبابية',
                      style: TextStyle(color: Colors.white70, fontSize: 11),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      );
    }

    // Android 11-: اختيار اللون
    return IslamicCard(
      title: 'لون شاشة الحجب',
      subtitle: _colorOptions
          .firstWhere((c) => c.color == _selectedColor, orElse: () => _colorOptions.first)
          .name,
      icon: Icons.palette_outlined,
      child: Column(
        children: [
          const SizedBox(height: 16),
          Wrap(
            spacing: 14,
            runSpacing: 14,
            alignment: WrapAlignment.center,
            children: _colorOptions.map((opt) {
              final bool sel = _selectedColor == opt.color;
              return GestureDetector(
                onTap: () async {
                  setState(() => _selectedColor = opt.color);
                  await _pushCurrentSettings();
                },
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    AnimatedContainer(
                      duration: const Duration(milliseconds: 250),
                      width: 46, height: 46,
                      decoration: BoxDecoration(
                        color:  opt.color,
                        shape:  BoxShape.circle,
                        border: Border.all(
                          color: sel ? _goldLight : Colors.white12,
                          width: sel ? 2.5 : 1,
                        ),
                        boxShadow: sel
                            ? [BoxShadow(color: _gold.withValues(alpha: 0.4), blurRadius: 10)]
                            : [],
                      ),
                      child: sel
                          ? Icon(Icons.check, color: _goldLight, size: 18)
                          : null,
                    ),
                    const SizedBox(height: 5),
                    Text(opt.name,
                        style: TextStyle(
                          fontSize: 10,
                          color: sel ? _goldLight : _textSub,
                        )),
                  ],
                ),
              );
            }).toList(),
          ),
          const SizedBox(height: 16),
          // معاينة
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: Container(
              height: 56,
              color: _surface2,
              padding: const EdgeInsets.all(8),
              child: Container(
                decoration: BoxDecoration(
                  color: _selectedColor,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Center(
                  child: Text('معاينة الحجب',
                      style: TextStyle(color: Colors.white38, fontSize: 12)),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildToggleButton() {
    return GestureDetector(
      onTap: _toggleProtection,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 400),
        width: double.infinity,
        height: 58,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          color: _isActive ? const Color(0xFF1A0A0A) : _surface,
          border: Border.all(
            color: _isActive
                ? Colors.red.shade900.withValues(alpha: 0.6)
                : _gold.withValues(alpha: 0.5),
            width: 1.5,
          ),
          boxShadow: [
            BoxShadow(
              color: _isActive
                  ? Colors.red.withValues(alpha: 0.15)
                  : _gold.withValues(alpha: 0.15),
              blurRadius: 16,
            ),
          ],
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              _isActive ? Icons.stop_circle_outlined : Icons.play_circle_outline,
              color: _isActive ? Colors.red.shade400 : _goldLight,
              size: 24,
            ),
            const SizedBox(width: 10),
            Text(
              _isActive ? 'إيقاف الحماية' : 'تفعيل الحماية',
              style: TextStyle(
                fontSize: 17,
                fontWeight: FontWeight.bold,
                color: _isActive ? Colors.red.shade400 : _goldLight,
                fontFamily: 'serif',
                letterSpacing: 1,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

