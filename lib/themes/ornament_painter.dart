import 'package:flutter/material.dart';

class OrnamentPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = const Color(0xFFD4AF37).withValues(alpha: 0.7)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.2
      ..strokeCap = StrokeCap.round;

    final path = Path()
      ..moveTo(0, size.height / 2)
      ..cubicTo(
        size.width * 0.3, 0,
        size.width * 0.6, 0,
        size.width * 0.8, size.height / 2,
      )
      ..cubicTo(
        size.width * 0.9, size.height,
        size.width, size.height / 2,
        size.width, size.height / 2,
      );
    canvas.drawPath(path, paint);

    canvas.drawCircle(Offset(size.width * 0.8, size.height / 2), 2, paint..style = PaintingStyle.fill);
  }

  @override
  bool shouldRepaint(_) => false;
}