resource "aws_sns_topic" "alerts" {
  count = var.alarm_email == "" ? 0 : 1
  name  = "${var.name}-${var.environment}-alerts"
}

resource "aws_sns_topic_subscription" "alerts" {
  count     = var.alarm_email == "" ? 0 : 1
  topic_arn = aws_sns_topic.alerts[0].arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

locals {
  alarm_actions = var.alarm_email == "" ? [] : [aws_sns_topic.alerts[0].arn]
}

resource "aws_cloudwatch_metric_alarm" "unhealthy" {
  alarm_name          = "${var.name}-${var.environment}-unhealthy-targets"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "UnHealthyHostCount"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  statistic           = "Maximum"
  threshold           = 0
  alarm_description   = "Frontend targets are failing the load balancer health check. Inspect the new tasks and roll back if the deployment did not already do so."
  alarm_actions       = local.alarm_actions
  dimensions = {
    LoadBalancer = aws_lb.edge.arn_suffix
    TargetGroup  = aws_lb_target_group.frontend.arn_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${var.name}-${var.environment}-database-cpu"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 60
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "Database CPU is above 80 percent. Check connection count and slow queries before scaling the instance."
  alarm_actions       = local.alarm_actions
  dimensions          = { DBInstanceIdentifier = aws_db_instance.postgres.id }
}

resource "aws_cloudwatch_metric_alarm" "rds_storage" {
  alarm_name          = "${var.name}-${var.environment}-database-storage"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  metric_name         = "FreeStorageSpace"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Minimum"
  threshold           = 2147483648
  alarm_description   = "Less than 2 GiB of database storage remains. Increase allocated storage before writes fail."
  alarm_actions       = local.alarm_actions
  dimensions          = { DBInstanceIdentifier = aws_db_instance.postgres.id }
}
