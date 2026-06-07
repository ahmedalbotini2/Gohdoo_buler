
// ── خلفية زخرفية مبسطة للشاشة ──────────────────────────────────────────────
import 'dart:math' as math;

import 'package:flutter/material.dart';

class IntroBackgroundPainter extends CustomPainter {
  final double t;
  final Color goldColor;
  IntroBackgroundPainter(this.t, this.goldColor);

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = goldColor.withValues(alpha: 0.03)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.0;

    final cx = size.width / 2;
    final cy = size.height * 0.2;
    final angle = t * 2 * math.pi;

    canvas.save();
    canvas.translate(cx, cy);
    canvas.rotate(angle);
    
    final path = Path();
    double r = 120;
    for (int i = 0; i < 16; i++) {
      final a = i * math.pi / 8;
      final radius = i.isEven ? r : r * 0.6;
      final x = radius * math.cos(a);
      final y = radius * math.sin(a);
      if (i == 0) path.moveTo(x, y);
      else path.lineTo(x, y);
    }
    path.close();
    canvas.drawPath(path, paint..color = goldColor.withValues(alpha: 0.05));
    canvas.restore();
  }

  @override
  bool shouldRepaint(IntroBackgroundPainter old) => old.t != t;
}