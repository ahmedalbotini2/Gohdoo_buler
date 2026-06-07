//الكود الخاص بالزخرفه الذهبية
import 'package:flutter/material.dart';
import 'package:safety_screen/themes/ornament_painter.dart';

class GoldOrnament extends StatelessWidget {
  final double size;
  final bool   mirror;
  const GoldOrnament({required this.size, this.mirror = false});

  @override
  Widget build(BuildContext context) {
    return Transform.scale(
      scaleX: mirror ? -1 : 1,
      child: CustomPaint(
        size: Size(size * 2, size),
        painter: OrnamentPainter(),
      ),
    );
  }
}