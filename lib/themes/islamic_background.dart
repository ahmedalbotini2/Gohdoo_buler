import 'dart:math' as math;
import 'package:flutter/material.dart';

class IslamicBackground extends StatelessWidget {
  final AnimationController controller;
  const IslamicBackground({required this.controller});

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (_, __) => CustomPaint(
        painter: IslamicPatternPainter(controller.value),
      ),
    );
  }
}

class IslamicPatternPainter extends CustomPainter {
  final double t;
  IslamicPatternPainter(this.t);

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = const Color(0xFFB8960C).withValues(alpha: 0.04)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 0.8;

    final cx = size.width / 2;
    final cy = size.height * 0.28;
    final angle = t * 2 * math.pi;

    // دوائر متداخلة 
    for (int i = 1; i <= 5; i++) {
      canvas.drawCircle(
        Offset(cx, cy),
        60.0 * i,
        paint..color = const Color(0xFFB8960C).withValues(alpha: 0.03 / i),
      );
    }

    // نجمة دوّارة
    _drawStar(canvas, Offset(cx, cy), 80, 8, angle, paint..color = const Color(0xFFD4AF37).withValues(alpha: 0.07));

    // زخرفة في الزوايا
    _drawCornerOrnament(canvas, const Offset(0, 0), size, paint..color = const Color(0xFFB8960C).withValues(alpha: 0.05));
  }

  void _drawStar(Canvas canvas, Offset center, double r, int points, double angle, Paint paint) {
    final path = Path();
    for (int i = 0; i < points * 2; i++) {
      final a = angle + i * math.pi / points;
      final radius = i.isEven ? r : r * 0.45;
      final x = center.dx + radius * math.cos(a);
      final y = center.dy + radius * math.sin(a);
      if (i == 0) path.moveTo(x, y);
      else path.lineTo(x, y);
    }
    path.close();
    canvas.drawPath(path, paint);
  }

  void _drawCornerOrnament(Canvas canvas, Offset origin, Size size, Paint paint) {
    final corners = [
      Offset(40, 40), Offset(size.width - 40, 40),
      Offset(40, size.height - 40), Offset(size.width - 40, size.height - 40),
    ];
    for (final c in corners) {
      for (int r = 1; r <= 3; r++) {
        canvas.drawCircle(c, r * 12.0, paint);
      }
    }
  }

  @override
  bool shouldRepaint(IslamicPatternPainter old) => old.t != t;
}
