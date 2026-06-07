import 'package:flutter/material.dart';

class IslamicCard extends StatelessWidget {
  final String   title;
  final String   subtitle;
  final IconData icon;
  final Widget   child;

  const IslamicCard({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.child,
  });

  static const Color _gold     = Color(0xFFB8960C);
  static const Color _goldLight = Color(0xFFD4AF37);
  static const Color _surface  = Color(0xFF141810);
  static const Color _textMain = Color(0xFFEDE8D0);
  static const Color textSub  = Color(0xFF8A8670);

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: _surface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: _gold.withValues(alpha: 0.2), width: 1),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, color: _gold, size: 18),
              const SizedBox(width: 8),
              Text(title,
                  style: const TextStyle(
                      fontSize: 14, fontWeight: FontWeight.bold, color: _textMain)),
              const Spacer(),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
                decoration: BoxDecoration(
                  color: _gold.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: _gold.withValues(alpha: 0.3), width: 1),
                ),
                child: Text(subtitle,
                    style: const TextStyle(
                        fontSize: 11, color: _goldLight, fontWeight: FontWeight.w600)),
              ),
            ],
          ),
          child,
        ],
      ),
    );
  }
}