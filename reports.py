"""Generate a PDF in memory from stored scan metadata."""

from datetime import datetime
from io import BytesIO
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

from guidance import CARE, NOTE, SOURCE


def make_report(record, summary):
    output = BytesIO()
    styles = getSampleStyleSheet()
    styles["Title"].textColor = colors.HexColor("#163D2A")
    scan_time = datetime.fromisoformat(record["time"]).astimezone().strftime("%d %B %Y, %H:%M %Z")
    report_time = datetime.now().astimezone().strftime("%d %B %Y, %H:%M %Z")
    story = [
        Paragraph("MangoCare AI", styles["Title"]),
        Paragraph("Leaf detection report", styles["Heading1"]),
        Paragraph(f"Scan {record['id']} | {scan_time}", styles["Normal"]),
        Paragraph(f"Report generated {report_time}", styles["Normal"]),
        Paragraph(f"{len(record['detections'])} detected objects", styles["Heading2"]),
    ]
    rows = [["Class", "Detections", "Mean confidence"]]
    for label, metric in summary.items():
        average = metric["average"]
        rows.append([label, metric["count"], f"{average:.1%}" if average is not None else "No detections"])
    table = Table(rows, colWidths=[160, 95, 150], hAlign="LEFT")
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#163D2A")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.HexColor("#EAF4EC"), colors.white]),
        ("TOPPADDING", (0, 0), (-1, -1), 8), ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
    ]))
    story.append(table)
    for label, metric in summary.items():
        if not metric["count"]:
            continue
        story.append(Paragraph(label, styles["Heading2"]))
        for heading, tips in CARE[label].items():
            if heading == "description":
                story.append(Paragraph(escape(tips), styles["Normal"]))
            else:
                story.append(Paragraph(f"<b>{escape(heading)}</b> — {escape(' '.join(tips))}", styles["Normal"]))
                story.append(Spacer(1, 4))
    story.extend([
        Spacer(1, 12),
        Paragraph(escape(NOTE), styles["Normal"]),
        Paragraph(f'<link href="{SOURCE}" color="#26664D">UF/IFAS mango care reference</link>', styles["Normal"]),
    ])
    SimpleDocTemplate(output, title="MangoCare AI detection report").build(story)
    output.seek(0)
    return output
