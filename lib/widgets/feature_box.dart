
import 'package:flutter/material.dart';

class FeatureBox extends StatelessWidget {
  final String title;
  final List<String> items;
  final bool isPro;

   FeatureBox({required this.title, required this.items, required this.isPro});

  @override
  Widget build(BuildContext context) {
    final color = isPro ? const Color(0xFF1A3C2A) : const Color(0xFF3C1A1A); // أخضر للمميزات، أحمر خفيف للعيوب
    final iconColor = isPro ? Colors.green.shade400 : Colors.red.shade400;
    final icon = isPro ? Icons.check_circle_outline : Icons.cancel_outlined;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF141810),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.5)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: TextStyle(fontWeight: FontWeight.bold, color: iconColor, fontFamily: 'serif')),
          const SizedBox(height: 10),
          ...items.map((item) => Padding(
                padding: const EdgeInsets.only(bottom: 6.0),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(icon, size: 16, color: iconColor),
                    const SizedBox(width: 8),
                    Expanded(child: Text(item, style: const TextStyle(color: Color(0xFFEDE8D0), fontSize: 12))),
                  ],
                ),
              )),
        ],
      ),
    );
  }
}
