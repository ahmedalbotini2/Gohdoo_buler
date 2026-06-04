import 'dart:math' as math;
import 'package:flutter/material.dart';

class GhuddooSplash extends StatefulWidget {
  final VoidCallback onComplete;
  const GhuddooSplash({super.key, required this.onComplete});

  @override
  State<GhuddooSplash> createState() => _GhuddooSplashState();
}

class _GhuddooSplashState extends State<GhuddooSplash>
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
              painter: _SplashPainter(
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

// ── Painter رئيسي ──────────────────────────────────────────────────────────
class _SplashPainter extends CustomPainter {
  final double bgRotate;
  final double eyeTopDraw;
  final double eyeBottomDraw;
  final double lashesOpacity;
  final double pupilRadius;
  final double pupilOpacity;
  final double writingProgress;
  final double dotsOpacity;
  final double subtitleOpacity;
  final double closeProgress;
  final Color gold, goldDim, textLight, textSub;

  const _SplashPainter({
    required this.bgRotate,
    required this.eyeTopDraw,
    required this.eyeBottomDraw,
    required this.lashesOpacity,
    required this.pupilRadius,
    required this.pupilOpacity,
    required this.writingProgress,
    required this.dotsOpacity,
    required this.subtitleOpacity,
    required this.closeProgress,
    required this.gold,
    required this.goldDim,
    required this.textLight,
    required this.textSub,
  });

  static const double _cx = 180; // مركز العين x
  static const double _cy = 120; // مركز العين y

  @override
  void paint(Canvas canvas, Size size) {
    _drawBgPattern(canvas, size);
    _drawEye(canvas);
    _drawWriting(canvas);
    _drawDots(canvas);
    _drawSubtitle(canvas, size);
  }

  // ── خلفية زخرفية ─────────────────────────────────────────────────────────
  void _drawBgPattern(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = gold.withValues(alpha: 0.04)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 0.7;

    final center = Offset(size.width / 2, size.height * 0.38);
    for (int i = 1; i <= 4; i++) {
      canvas.drawCircle(center, 55.0 * i, paint);
    }
    // نجمة ثمانية دوّارة
    canvas.save();
    canvas.translate(center.dx, center.dy);
    canvas.rotate(bgRotate);
    _drawStar(canvas, Offset.zero, 70, paint..color = gold.withValues(alpha: 0.06));
    canvas.restore();
  }

  void _drawStar(Canvas canvas, Offset center, double r, Paint paint) {
    final path = Path();
    for (int i = 0; i < 16; i++) {
      final angle = i * math.pi / 8;
      final radius = i.isEven ? r : r * 0.45;
      final x = center.dx + radius * math.cos(angle);
      final y = center.dy + radius * math.sin(angle);
      if (i == 0) path.moveTo(x, y);
      else path.lineTo(x, y);
    }
    path.close();
    canvas.drawPath(path, paint);
  }

  // ── رسم العين ─────────────────────────────────────────────────────────────
  void _drawEye(Canvas canvas) {
    final eyePaint = Paint()
      ..color = gold
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.5
      ..strokeCap = StrokeCap.round;

    // الجفنان عند الإغماض يلتقيان
    final topCtrlY  = lerpDouble(60.0, _cy - 2, closeProgress);
    final botCtrlY  = lerpDouble(180.0, _cy + 2, closeProgress);

    // ── الجفن العلوي ──────────────────────────────────────────────────────
    final topPath = Path()
      ..moveTo(80, _cy)
      ..quadraticBezierTo(_cx, topCtrlY, 280, _cy);

    final topMetrics = topPath.computeMetrics().first;
    final topDraw = topMetrics.extractPath(0, topMetrics.length * (1 - eyeTopDraw));
    canvas.drawPath(topDraw, eyePaint..color = gold.withValues(alpha: closeProgress < 1 ? 1 : 0.3));

    // ── الجفن السفلي ──────────────────────────────────────────────────────
    if (eyeTopDraw < 0.5) {
      final botPath = Path()
        ..moveTo(80, _cy)
        ..quadraticBezierTo(_cx, botCtrlY, 280, _cy);

      final botMetrics = botPath.computeMetrics().first;
      final botDraw = botMetrics.extractPath(0, botMetrics.length * (1 - eyeBottomDraw));
      canvas.drawPath(botDraw, eyePaint..color = gold.withValues(alpha: 1 - closeProgress * 0.8));
    }

    // ── الرموش ────────────────────────────────────────────────────────────
    if (lashesOpacity > 0 && closeProgress < 0.5) {
      _drawLashes(canvas, lashesOpacity * (1 - closeProgress * 2).clamp(0, 1));
    }

    // ── البؤبؤ ─────────────────────────────────────────────────────────────
    if (pupilOpacity > 0 && closeProgress < 0.8) {
      final pOpacity = (pupilOpacity * (1 - closeProgress * 1.3)).clamp(0.0, 1.0);
      final r = pupilRadius * 22 * (1 - closeProgress);

      canvas.drawCircle(
        const Offset(_cx, _cy),
        r + 6,
        Paint()..color = gold.withValues(alpha: pOpacity * 0.3)..style = PaintingStyle.stroke..strokeWidth = 1.5,
      );
      canvas.drawCircle(
        const Offset(_cx, _cy), r,
        Paint()..color = gold.withValues(alpha: pOpacity * 0.15),
      );
      // بريق
      canvas.drawCircle(
        Offset(_cx - 5, _cy - 6), r * 0.2,
        Paint()..color = const Color(0xFFFFF8DC).withValues(alpha: pOpacity * 0.8),
      );
    }
  }

  void _drawLashes(Canvas canvas, double opacity) {
    final paint = Paint()
      ..color = gold.withValues(alpha: opacity * 0.8)
      ..strokeWidth = 1.2
      ..strokeCap = StrokeCap.round;

    final lashes = [
      [110.0, 97.0, 102.0, 82.0],
      [138.0, 78.0, 134.0, 62.0],
      [165.0, 68.0, 164.0, 52.0],
      [193.0, 65.0, 194.0, 49.0],
      [220.0, 70.0, 224.0, 54.0],
      [245.0, 82.0, 252.0, 67.0],
      [265.0, 100.0, 275.0, 87.0],
    ];

    for (final l in lashes) {
      canvas.drawLine(Offset(l[0], l[1]), Offset(l[2], l[3]), paint);
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  // توقيع "غُضُّوا" بالخط الكوفي — كأن شخصاً يكتبها بالقلم
  //
  // ترتيب الكتابة (من اليمين لليسار):
  //   [0.00–0.18]  غ   : قوس علوي + جسم + ذيل
  //   [0.18–0.42]  ضُّ  : قاعدة أفقية + جسم + رأس مربع
  //   [0.42–0.62]  كشيدة: خط أفقي طويل يمتد يساراً
  //   [0.62–0.80]  و   : رأس دائري + ذيل منحنٍ
  //   [0.80–1.00]  ا   : ألف رأسية مائلة
  //
  // بعد اكتمال writingProgress تظهر: النقاط، الشدة، الضمات
  // ══════════════════════════════════════════════════════════════════════════
  void _drawWriting(Canvas canvas) {
    if (writingProgress <= 0) return;

    // ── قلم الكتابة: عريض للخطوط الرئيسية، رفيع للتفاصيل ─────────────────
    final thickPaint = Paint()
      ..color = textLight
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3.8
      ..strokeCap = StrokeCap.square   // طرف مربع = طابع كوفي
      ..strokeJoin = StrokeJoin.miter;

    final thinPaint = Paint()
      ..color = textLight
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.6
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;

    // ════════════════════════════════════════════════════════════════════════
    // 1. حرف غ  [0.00 → 0.18]
    //    رسم: قوس علوي ← جسم مربع ← ذيل نازل
    // ════════════════════════════════════════════════════════════════════════
    _drawSegment(canvas, 0.00, 0.07, thickPaint, () {
      // الرأس: قوس كوفي علوي
      return Path()
        ..moveTo(278, 178)
        ..cubicTo(278, 164, 268, 155, 256, 157)
        ..cubicTo(244, 159, 238, 169, 240, 178);
    });

    _drawSegment(canvas, 0.07, 0.13, thickPaint, () {
      // الجسم: قاعدة أفقية
      return Path()
        ..moveTo(240, 178)
        ..lineTo(240, 185)
        ..cubicTo(240, 192, 248, 196, 258, 194)
        ..lineTo(278, 190);
    });

    _drawSegment(canvas, 0.13, 0.18, thinPaint, () {
      // الذيل: نزلة خفيفة لليمين
      return Path()
        ..moveTo(278, 190)
        ..cubicTo(283, 192, 285, 196, 282, 200);
    });

    // ════════════════════════════════════════════════════════════════════════
    // 2. حرف ضُّ  [0.18 → 0.42]
    //    رسم: خط أفقي علوي ← جانب أيمن نازل ← قاعدة ← جانب أيسر صاعد ← رأس
    // ════════════════════════════════════════════════════════════════════════
    _drawSegment(canvas, 0.18, 0.25, thickPaint, () {
      // السقف الأفقي
      return Path()
        ..moveTo(236, 165)
        ..lineTo(196, 165);
    });

    _drawSegment(canvas, 0.25, 0.30, thickPaint, () {
      // الجانب الأيمن
      return Path()
        ..moveTo(236, 165)
        ..lineTo(236, 185);
    });

    _drawSegment(canvas, 0.30, 0.36, thickPaint, () {
      // القاعدة
      return Path()
        ..moveTo(236, 185)
        ..lineTo(196, 185);
    });

    _drawSegment(canvas, 0.36, 0.42, thickPaint, () {
      // الجانب الأيسر — يصعد ليلتقي بالسقف
      return Path()
        ..moveTo(196, 185)
        ..lineTo(196, 165);
    });

    // ════════════════════════════════════════════════════════════════════════
    // 3. كشيدة  [0.42 → 0.62]
    //    خط أفقي ممتد طويل من الضاد نحو الواو
    //    يُرسم بسرعة "جرّة" واحدة — كما يفعل الخطاط
    // ════════════════════════════════════════════════════════════════════════
    _drawSegment(canvas, 0.42, 0.62, thickPaint, () {
      return Path()
        ..moveTo(196, 175)
        ..lineTo(145, 175);
    });

    // ════════════════════════════════════════════════════════════════════════
    // 4. حرف و  [0.62 → 0.80]
    //    رأس بيضوي صغير + ذيل منحنٍ لليسار وللأسفل
    // ════════════════════════════════════════════════════════════════════════
    _drawSegment(canvas, 0.62, 0.70, thickPaint, () {
      // الرأس الكوفي
      return Path()
        ..moveTo(145, 168)
        ..cubicTo(145, 161, 135, 159, 129, 163)
        ..cubicTo(123, 168, 124, 176, 130, 178)
        ..lineTo(145, 178);
    });

    _drawSegment(canvas, 0.70, 0.80, thinPaint, () {
      // الذيل المنحني
      return Path()
        ..moveTo(130, 178)
        ..cubicTo(122, 180, 116, 188, 120, 196)
        ..cubicTo(124, 203, 134, 202, 136, 195);
    });

    // ════════════════════════════════════════════════════════════════════════
    // 5. حرف ا  [0.80 → 1.00]
    //    ألف كوفية: عمودية مع انحناءة خفيفة في الأسفل
    // ════════════════════════════════════════════════════════════════════════
    _drawSegment(canvas, 0.80, 1.00, thickPaint, () {
      return Path()
        ..moveTo(108, 158)
        ..lineTo(108, 192)
        ..cubicTo(108, 197, 112, 199, 116, 197);
    });

    // ════════════════════════════════════════════════════════════════════════
    // 6. التشكيل والنقاط — تظهر تدريجياً بعد اكتمال الكلمة
    // ════════════════════════════════════════════════════════════════════════
    if (writingProgress > 0.82) {
      final t = ((writingProgress - 0.82) / 0.18).clamp(0.0, 1.0);

      // نقطة غ (فوق)
      _drawDot(canvas, const Offset(258, 150), t);

      // نقطتا ض (فوق السقف)
      _drawDot(canvas, const Offset(208, 155), t);
      _drawDot(canvas, const Offset(222, 155), t);

      // ضمة غُ
      _drawDamma(canvas, const Offset(262, 144), t);

      // شدة ضّ
      _drawShadda(canvas, const Offset(212, 152), t);

      // ضمة ضُ (فوق الشدة)
      _drawDamma(canvas, const Offset(212, 144), t * 0.85);
    }
  }

  // ── مساعد: يرسم جزءاً من المسار بناءً على نسبة التقدم الكلية ─────────────
  void _drawSegment(
    Canvas canvas,
    double segStart,
    double segEnd,
    Paint paint,
    Path Function() buildPath,
  ) {
    if (writingProgress <= segStart) return;
    final local = ((writingProgress - segStart) / (segEnd - segStart)).clamp(0.0, 1.0);
    final path = buildPath();
    final metrics = path.computeMetrics().first;
    final drawn = metrics.extractPath(0, metrics.length * local);
    canvas.drawPath(drawn, paint);
  }

  // ── نقطة الحرف ────────────────────────────────────────────────────────────
  void _drawDot(Canvas canvas, Offset pos, double opacity) {
    canvas.drawCircle(
      pos,
      2.8,
      Paint()..color = gold.withValues(alpha: opacity),
    );
  }

  void _drawDamma(Canvas canvas, Offset pos, double opacity) {
    final paint = Paint()
      ..color = goldDim.withValues(alpha: opacity)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.4
      ..strokeCap = StrokeCap.round;
    // ضمة: واو صغيرة فوق الحرف
    final path = Path()
      ..moveTo(pos.dx, pos.dy)
      ..cubicTo(pos.dx + 3, pos.dy - 6, pos.dx + 9, pos.dy - 6, pos.dx + 8, pos.dy)
      ..cubicTo(pos.dx + 7, pos.dy + 3, pos.dx + 3, pos.dy + 2, pos.dx + 3, pos.dy - 1);
    canvas.drawPath(path, paint);
  }

  void _drawShadda(Canvas canvas, Offset pos, double opacity) {
    final paint = Paint()
      ..color = goldDim.withValues(alpha: opacity)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.3
      ..strokeCap = StrokeCap.round;
    // شدة: حرف ش مصغر
    final path = Path()
      ..moveTo(pos.dx - 5, pos.dy)
      ..cubicTo(pos.dx - 3, pos.dy - 7, pos.dx + 3, pos.dy - 7, pos.dx + 5, pos.dy)
      ..moveTo(pos.dx - 1, pos.dy - 4)
      ..lineTo(pos.dx - 1, pos.dy - 8);
    canvas.drawPath(path, paint);
  }

  // ── النقاط ────────────────────────────────────────────────────────────────
  void _drawDots(Canvas canvas) {
    if (dotsOpacity <= 0) return;
    final paint = Paint()..color = gold.withValues(alpha: dotsOpacity);
    // نقطة غ
    canvas.drawCircle(const Offset(250, 150), 2.5, paint);
    // نقطتا ض
    canvas.drawCircle(const Offset(196, 147), 2.2, paint);
    canvas.drawCircle(const Offset(206, 147), 2.2, paint);
  }

  // ── النص الفرعي ──────────────────────────────────────────────────────────
  void _drawSubtitle(Canvas canvas, Size size) {
    if (subtitleOpacity <= 0) return;

    // الآية في سطرين
    final lines = [
      'قُل لِّلْمُؤْمِنِينَ يَغُضُّوا',
      'مِنْ أَبْصَارِهِمْ',
    ];

    final linePaint = Paint()
      ..color = gold.withValues(alpha: subtitleOpacity * 0.35)
      ..strokeWidth = 0.8;

    double startY = size.height * 0.76;
    const double lineSpacing = 22.0;

    for (int i = 0; i < lines.length; i++) {
      final tp = TextPainter(
        text: TextSpan(
          text: lines[i],
          style: TextStyle(
            color: textSub.withValues(alpha: subtitleOpacity),
            fontSize: i == 0 ? 13 : 12,
            letterSpacing: 1.5,
            fontFamily: 'serif',
            height: 1.6,
          ),
        ),
        textDirection: TextDirection.rtl,
      )..layout(maxWidth: size.width - 40);

      final textX = (size.width - tp.width) / 2;
      final textY = startY + i * lineSpacing;

      // خط ذهبي فقط للسطر الأول
      if (i == 0) {
        final midY = textY + tp.height / 2;
        canvas.drawLine(Offset(textX - 28, midY), Offset(textX - 6, midY), linePaint);
        canvas.drawLine(Offset(textX + tp.width + 6, midY), Offset(textX + tp.width + 28, midY), linePaint);
      }

      tp.paint(canvas, Offset(textX, textY));
    }

    // سورة المصدر
    final srcPainter = TextPainter(
      text: TextSpan(
        text: '— سورة النور ٣٠',
        style: TextStyle(
          color: gold.withValues(alpha: subtitleOpacity * 0.55),
          fontSize: 10,
          letterSpacing: 1,
          fontFamily: 'serif',
        ),
      ),
      textDirection: TextDirection.rtl,
    )..layout();

    srcPainter.paint(
      canvas,
      Offset((size.width - srcPainter.width) / 2, startY + lines.length * lineSpacing + 4),
    );
  }

  @override
  bool shouldRepaint(_SplashPainter old) => true;

  static double lerpDouble(double a, double b, double t) => a + (b - a) * t;
}