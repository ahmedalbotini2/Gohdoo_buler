import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:safety_screen/themes/splash_painter.dart';

class SplashScreen extends StatefulWidget {
  final VoidCallback onComplete;
  const SplashScreen({super.key, required this.onComplete});

  @override
  State<SplashScreen> createState() => _GhuddooSplashState();
}

class _GhuddooSplashState extends State<SplashScreen>
    with TickerProviderStateMixin {

  // ── ألوان ─────────────────────────────────────────────────────────────────
  static const Color _bg        = Color(0xFF0C0E0B);
  static const Color _gold      = Color(0xFFD4AF37);
  static const Color _goldDim   = Color(0xFFB8960C);
  static const Color _textLight = Color(0xFFEDE8D0);
  static const Color _textSub   = Color(0xFF8A8670);

  // ── Controllers ───────────────────────────────────────────────────────────
  late AnimationController _eyeTopCtrl;
  late AnimationController _eyeBottomCtrl;
  late AnimationController _lashesCtrl;
  late AnimationController _pupilCtrl;
  late AnimationController _writingCtrl;   // توقيع الكلمة كاملة
  late AnimationController _dotsCtrl;
  late AnimationController _subtitleCtrl;
  late AnimationController _closeCtrl;     // إغماضة العين
  late AnimationController _bgRotateCtrl;

  // ── Animations ────────────────────────────────────────────────────────────
  late Animation<double> _eyeTopDraw;
  late Animation<double> _eyeBottomDraw;
  late Animation<double> _lashesOpacity;
  late Animation<double> _pupilRadius;
  late Animation<double> _pupilOpacity;
  late Animation<double> _writingProgress; // 0→1 يحرك كل الحروف بالتسلسل
  late Animation<double> _dotsOpacity;
  late Animation<double> _subtitleOpacity;
  late Animation<double> _closeProgress;  // إغلاق العين 0→1
  late Animation<double> _bgRotate;

  @override
  void initState() {
    super.initState();
    _setupControllers();
    _setupAnimations();
    _playSequence();
  }

  void _setupControllers() {
    _eyeTopCtrl    = AnimationController(vsync: this, duration: const Duration(milliseconds: 900));
    _eyeBottomCtrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 800));
    _lashesCtrl    = AnimationController(vsync: this, duration: const Duration(milliseconds: 400));
    _pupilCtrl     = AnimationController(vsync: this, duration: const Duration(milliseconds: 600));
    _writingCtrl   = AnimationController(vsync: this, duration: const Duration(milliseconds: 2200));
    _dotsCtrl      = AnimationController(vsync: this, duration: const Duration(milliseconds: 350));
    _subtitleCtrl  = AnimationController(vsync: this, duration: const Duration(milliseconds: 500));
    _closeCtrl     = AnimationController(vsync: this, duration: const Duration(milliseconds: 600));
    _bgRotateCtrl  = AnimationController(vsync: this, duration: const Duration(seconds: 30))..repeat();
  }

  void _setupAnimations() {
    _eyeTopDraw     = Tween(begin: 1.0, end: 0.0).animate(CurvedAnimation(parent: _eyeTopCtrl,    curve: Curves.easeOut));
    _eyeBottomDraw  = Tween(begin: 1.0, end: 0.0).animate(CurvedAnimation(parent: _eyeBottomCtrl, curve: Curves.easeOut));
    _lashesOpacity  = Tween(begin: 0.0, end: 1.0).animate(CurvedAnimation(parent: _lashesCtrl,    curve: Curves.easeIn));
    _pupilRadius    = Tween(begin: 0.0, end: 1.0).animate(CurvedAnimation(parent: _pupilCtrl,     curve: Curves.elasticOut));
    _pupilOpacity   = Tween(begin: 0.0, end: 1.0).animate(CurvedAnimation(parent: _pupilCtrl,     curve: Curves.easeIn));
    _writingProgress = Tween(begin: 0.0, end: 1.0).animate(CurvedAnimation(parent: _writingCtrl,  curve: Curves.easeInOut));
    _dotsOpacity    = Tween(begin: 0.0, end: 1.0).animate(CurvedAnimation(parent: _dotsCtrl,      curve: Curves.easeIn));
    _subtitleOpacity = Tween(begin: 0.0, end: 1.0).animate(CurvedAnimation(parent: _subtitleCtrl, curve: Curves.easeIn));
    _closeProgress  = Tween(begin: 0.0, end: 1.0).animate(CurvedAnimation(parent: _closeCtrl,     curve: Curves.easeInOut));
    _bgRotate       = Tween(begin: 0.0, end: 2 * math.pi).animate(_bgRotateCtrl);
  }

  Future<void> _playSequence() async {
    await Future.delayed(const Duration(milliseconds: 300));

    // 1. رسم الجفن العلوي
    await _eyeTopCtrl.forward();

    // 2. رسم الجفن السفلي
    await _eyeBottomCtrl.forward();

    // 3. الرموش + البؤبؤ معاً
    _lashesCtrl.forward();
    await _pupilCtrl.forward();
    await Future.delayed(const Duration(milliseconds: 200));

    // 4. توقيع الكلمة
    await _writingCtrl.forward();
    await Future.delayed(const Duration(milliseconds: 200));

    // 5. النقاط والتشكيل
    await _dotsCtrl.forward();
    await Future.delayed(const Duration(milliseconds: 400));

    // 6. النص الفرعي
    await _subtitleCtrl.forward();
    await Future.delayed(const Duration(milliseconds: 900));

    // 7. إغماضة العين
    await _closeCtrl.forward();
    await Future.delayed(const Duration(milliseconds: 400));

    // 8. الانتقال للشاشة الرئيسية
    widget.onComplete();
  }

  @override
  void dispose() {
    _eyeTopCtrl.dispose();
    _eyeBottomCtrl.dispose();
    _lashesCtrl.dispose();
    _pupilCtrl.dispose();
    _writingCtrl.dispose();
    _dotsCtrl.dispose();
    _subtitleCtrl.dispose();
    _closeCtrl.dispose();
    _bgRotateCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _bg,
      body: Center(
        child: AnimatedBuilder(
          animation: Listenable.merge([
            _eyeTopCtrl, _eyeBottomCtrl, _lashesCtrl, _pupilCtrl,
            _writingCtrl, _dotsCtrl, _subtitleCtrl, _closeCtrl, _bgRotateCtrl,
          ]),
          builder: (context, _) {
            return CustomPaint(
              size: const Size(360, 320),
              painter: SplashPainter(
                bgRotate:        _bgRotate.value,
                eyeTopDraw:      _eyeTopDraw.value,
                eyeBottomDraw:   _eyeBottomDraw.value,
                lashesOpacity:   _lashesOpacity.value,
                pupilRadius:     _pupilRadius.value,
                pupilOpacity:    _pupilOpacity.value,
                writingProgress: _writingProgress.value,
                dotsOpacity:     _dotsOpacity.value,
                subtitleOpacity: _subtitleOpacity.value,
                closeProgress:   _closeProgress.value,
                gold:      _gold,
                goldDim:   _goldDim,
                textLight: _textLight,
                textSub:   _textSub,
              ),
            );
          },
        ),
      ),
    );
  }
}
