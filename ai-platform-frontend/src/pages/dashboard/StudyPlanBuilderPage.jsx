import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../../api';
import {
    Typography,
    Card,
    CardHeader,
    CardBody,
    Input,
    Button,
    Chip,
    Spinner,
    Alert,
    Select,
    Option,
    IconButton,
} from "@material-tailwind/react";
import {
    SparklesIcon,
    ExclamationTriangleIcon,
    BookOpenIcon,
    CheckCircleIcon,
    TrashIcon,
    ArrowUpTrayIcon,
} from '@heroicons/react/24/solid';
import toast from 'react-hot-toast';
import { useTheme } from "@/context/ThemeContext.jsx";
import RecommendationCard from "@/components/RecommendationCard";

const StudyPlanBuilderPage = () => {
    const [topic, setTopic] = useState('');
    const [difficulty, setDifficulty] = useState('Beginner');
    const [durationDays, setDurationDays] = useState(7);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [history, setHistory] = useState([]);
    const [historyLoading, setHistoryLoading] = useState(true);

    // Syllabus Upload State
    const [activeTab, setActiveTab] = useState('topic');
    const [selectedFile, setSelectedFile] = useState(null);

    const navigate = useNavigate();
    const { theme } = useTheme();
    const [loadingStage, setLoadingStage] = useState(0);

    // Fusion Feature: Smart Suggestions
    const [recommendations, setRecommendations] = useState(null);

    useEffect(() => {
        let interval;
        if (loading) {
            interval = setInterval(() => {
                setLoadingStage((prev) => (prev + 1) % 4);
            }, 2000);
        } else {
            setLoadingStage(0);
        }
        return () => clearInterval(interval);
    }, [loading]);

    useEffect(() => {
        fetchHistory();
        fetchRecommendations();
    }, []);

    const fetchRecommendations = async () => {
        try {
            const res = await api.get('/recommendations/dashboard');
            setRecommendations(res.data);
        } catch (err) {
            console.error("Failed to load recommendations:", err);
            setRecommendations([]);
        }
    };

    const fetchHistory = async () => {
        try {
            const response = await api.get('/study-plans?summary=true');
            setHistory(response.data);
        } catch (err) {
            console.error("Failed to load history:", err);
        } finally {
            setHistoryLoading(false);
        }
    };

    const handleDelete = async (planId, e) => {
        e.stopPropagation();
        if (!window.confirm("Are you sure you want to delete this study plan?")) return;

        try {
            await api.delete(`/study-plans/${planId}`);
            setHistory(history.filter(p => p.id !== planId));
            toast.success("Study plan deleted successfully");
        } catch (err) {
            console.error("Failed to delete study plan:", err);
            toast.error("Failed to delete study plan. Please try again.");
        }
    };

    const handleFileChange = (e) => {
        if (e.target.files && e.target.files[0]) {
            const file = e.target.files[0];
            if (file.size > 10 * 1024 * 1024) { // 10MB limit
                setError("File size must be less than 10MB");
                return;
            }
            setSelectedFile(file);
            setError(null);
        }
    };

    const handleGenerate = async (e) => {
        e.preventDefault();
        setLoading(true);
        setError(null);

        try {
            if (activeTab === 'syllabus') {
                if (!selectedFile) {
                    setError("Please upload a syllabus file.");
                    setLoading(false);
                    return;
                }

                const formData = new FormData();
                formData.append('file', selectedFile);
                formData.append('durationDays', durationDays);

                const response = await api.post('/study-plans/generate-from-syllabus', formData, {
                    headers: {
                        'Content-Type': 'multipart/form-data',
                    },
                });
                navigate(`/dashboard/study-plan/${response.data.id}`);

            } else {
                if (!topic.trim()) {
                    setError("Please enter a topic.");
                    setLoading(false);
                    return;
                }

                const response = await api.post('/study-plans/generate', {
                    topic,
                    difficulty,
                    durationDays,
                });
                navigate(`/dashboard/study-plan/${response.data.id}`);
            }
        } catch (err) {
            console.error(err);
            setError(err.response?.data?.error || "Failed to generate study plan. Please try again.");
        } finally {
            setLoading(false);
        }
    };

    const durationOptions = [
        { value: 3, label: '3 Days', sub: 'Quick Sprint' },
        { value: 7, label: '1 Week', sub: 'Standard' },
        { value: 14, label: '2 Weeks', sub: 'Deep Dive' },
        { value: 30, label: '1 Month', sub: 'Mastery' },
    ];

    const formatDate = (dateStr) => {
        const date = new Date(dateStr);
        return date.toLocaleDateString('en-US', {
            month: 'short', day: 'numeric', year: 'numeric'
        });
    };

    return (
        <div className="mt-12 space-y-8">
            {/* Header hidden as it is integrated into the card now, matching Practice page style */}

            <div className="flex flex-col gap-8">
                {/* Builder Form Card - Matching Practice.jsx style */}
                <Card className="overflow-visible relative z-20 border border-blue-100/60 dark:border-gray-700 bg-white/80 dark:bg-gray-900/80 backdrop-blur-md shadow-sm">
                    <CardHeader floated={false} shadow={false} className="rounded-t-xl bg-gradient-to-r from-blue-600 to-blue-400 px-6 py-4 m-0">
                        <div className="flex items-center gap-3">
                            <div className="p-2 bg-white/20 rounded-lg shadow-inner">
                                <SparklesIcon className="h-6 w-6 text-white animate-pulse" />
                            </div>
                            <div>
                                <Typography variant="h5" color="white" className="font-bold tracking-tight">Study Plan Builder</Typography>
                                <Typography variant="small" color="white" className="opacity-90 font-normal">Enter a topic and we'll curate a schedule for you.</Typography>
                            </div>
                        </div>
                    </CardHeader>

                    <CardBody className="p-6 md:p-8 overflow-visible">
                        {loading ? (
                            <div className="flex flex-col items-center justify-center py-12 space-y-6">
                                <div className="relative">
                                    <div className="absolute inset-0 bg-blue-500 blur-xl opacity-20 animate-pulse rounded-full"></div>
                                    <Spinner className="h-16 w-16 text-blue-500" />
                                </div>
                                <div className="text-center space-y-2">
                                    <Typography variant="h5" color="blue-gray" className="dark:text-white animate-pulse">
                                        {activeTab === 'topic' ? "Generating your personalized plan..." : "Analyzing your syllabus..."}
                                    </Typography>
                                    <Typography variant="small" className="text-gray-500 dark:text-gray-400 font-normal">
                                        {activeTab === 'topic'
                                            ? ["Analyzing your topic...", "Curating best YouTube videos...", "Drafting practice quizzes...", "Finalizing your schedule..."][loadingStage]
                                            : ["Reading document structure...", "Extracting key chapters...", "Finding relevant playlists...", "Building your custom curriculum..."][loadingStage]
                                        }
                                    </Typography>
                                    <Typography variant="small" className="text-blue-gray-400 dark:text-gray-500 italic mt-2">
                                        This process may take 3-5 minutes. Grab a cup of coffee meanwhile! ☕
                                    </Typography>
                                </div>
                            </div>
                        ) : (
                            <div className="flex flex-col gap-6">
                                {/* Tabs */}
                                <div className="flex p-1 bg-blue-gray-50 dark:bg-gray-800 rounded-xl relative">
                                    <button
                                        onClick={() => setActiveTab('topic')}
                                        className={`flex-1 py-2 text-sm font-medium rounded-lg transition-all duration-300 ${activeTab === 'topic'
                                            ? 'bg-white dark:bg-gray-700 text-blue-600 dark:text-blue-400 shadow-sm'
                                            : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200'
                                            }`}
                                    >
                                        Enter Topic
                                    </button>
                                    <button
                                        onClick={() => setActiveTab('syllabus')}
                                        className={`flex-1 py-2 text-sm font-medium rounded-lg transition-all duration-300 ${activeTab === 'syllabus'
                                            ? 'bg-white dark:bg-gray-700 text-blue-600 dark:text-blue-400 shadow-sm'
                                            : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200'
                                            }`}
                                    >
                                        Upload Syllabus
                                    </button>
                                </div>

                                <form onSubmit={handleGenerate} className="flex flex-col gap-6">
                                    {activeTab === 'topic' ? (
                                        <>
                                            <div>
                                                <Input
                                                    size="lg"
                                                    label="What do you want to learn?"
                                                    placeholder="e.g. React Patterns, Machine Learning"
                                                    value={topic}
                                                    onChange={(e) => setTopic(e.target.value)}
                                                    color="blue"
                                                    className="!text-blue-gray-900 dark:!text-white !bg-white dark:!bg-gray-800/80 !border-blue-gray-200 focus:!border-blue-500 placeholder:text-blue-gray-300 dark:placeholder:text-gray-500"
                                                    labelProps={{
                                                        className: "!text-blue-gray-500 dark:!text-gray-300",
                                                    }}
                                                    disabled={loading}
                                                />
                                                {/* Smart Suggestions */}
                                                <div className="mt-4 flex flex-wrap gap-3 animate-fade-in">
                                                    {recommendations && recommendations.length > 0 && (
                                                        <>
                                                            <Typography variant="small" className="w-full font-bold text-blue-gray-500 dark:text-gray-400 mb-1">
                                                                Recommended for you:
                                                            </Typography>
                                                            {recommendations.slice(0, 4).map((rec, idx) => (
                                                                <RecommendationCard
                                                                    key={idx}
                                                                    recommendation={{ ...rec, action: "Create Plan" }}
                                                                    compact={true}
                                                                    onAction={(r) => setTopic(r.topic)}
                                                                />
                                                            ))}
                                                        </>
                                                    )}
                                                </div>
                                            </div>

                                            <div className="w-full relative">
                                                <Select
                                                    size="lg"
                                                    label="Difficulty"
                                                    value={difficulty}
                                                    onChange={(val) => setDifficulty(val)}
                                                    color="blue"
                                                    className="!text-blue-gray-900 dark:!text-white !bg-white dark:!bg-gray-800 !border-blue-gray-200 focus:!border-blue-500"
                                                    labelProps={{
                                                        className: "!text-blue-gray-500 dark:!text-gray-400",
                                                    }}
                                                    disabled={loading}
                                                    menuProps={{
                                                        className: "p-2 bg-white dark:bg-gray-900 border border-blue-gray-50 dark:border-gray-800 shadow-lg shadow-blue-gray-500/10 dark:shadow-black/50 rounded-xl min-w-[200px] max-h-[300px] overflow-y-auto z-[9999]",
                                                        animate: {
                                                            mount: { y: 0, scale: 1, opacity: 1 },
                                                            unmount: { y: 10, scale: 0.95, opacity: 0 },
                                                        },
                                                    }}
                                                >
                                                    <Option value="Beginner" className="mb-1 rounded-lg py-2.5 px-3 text-sm font-medium transition-all hover:bg-blue-50 hover:text-blue-700 focus:bg-blue-50 focus:text-blue-700 dark:text-gray-300 dark:hover:bg-gray-800 dark:hover:text-blue-300 dark:focus:bg-gray-800">Beginner</Option>
                                                    <Option value="Intermediate" className="mb-1 rounded-lg py-2.5 px-3 text-sm font-medium transition-all hover:bg-blue-50 hover:text-blue-700 focus:bg-blue-50 focus:text-blue-700 dark:text-gray-300 dark:hover:bg-gray-800 dark:hover:text-blue-300 dark:focus:bg-gray-800">Intermediate</Option>
                                                    <Option value="Advanced" className="mb-1 rounded-lg py-2.5 px-3 text-sm font-medium transition-all hover:bg-blue-50 hover:text-blue-700 focus:bg-blue-50 focus:text-blue-700 dark:text-gray-300 dark:hover:bg-gray-800 dark:hover:text-blue-300 dark:focus:bg-gray-800">Advanced</Option>
                                                </Select>
                                            </div>
                                        </>
                                    ) : (
                                        <div className="flex flex-col gap-4">
                                            <div className="border-2 border-dashed border-blue-gray-200 dark:border-gray-700 rounded-xl p-8 text-center hover:bg-blue-50/50 dark:hover:bg-gray-800/50 transition-colors cursor-pointer relative group">
                                                <input
                                                    type="file"
                                                    accept=".pdf"
                                                    className="absolute inset-0 w-full h-full opacity-0 cursor-pointer z-10"
                                                    onChange={handleFileChange}
                                                />
                                                <div className="flex flex-col items-center gap-3">
                                                    <div className="p-3 bg-blue-50 dark:bg-gray-800 rounded-full group-hover:scale-110 transition-transform">
                                                        <ArrowUpTrayIcon className="h-6 w-6 text-blue-500" />
                                                    </div>
                                                    <div>
                                                        <Typography variant="h6" color="blue-gray" className="dark:text-white">
                                                            {selectedFile ? selectedFile.name : "Click or Drag to Upload Syllabus"}
                                                        </Typography>
                                                        <Typography variant="small" className="text-gray-500 dark:text-gray-400 font-normal mt-1">
                                                            Supports PDF only (Max 10MB)
                                                        </Typography>
                                                    </div>
                                                </div>
                                            </div>
                                            {selectedFile && (
                                                <Alert color="green" variant="ghost" className="py-2 px-4 text-sm flex items-center">
                                                    <CheckCircleIcon className="h-4 w-4 mr-2" />
                                                    Ready to analyze: {selectedFile.name}
                                                </Alert>
                                            )}
                                        </div>
                                    )}

                                    {/* Duration Selector - Always Visible */}
                                    <div className="w-full relative">
                                        <Select
                                            size="lg"
                                            label="Course Duration"
                                            value={String(durationDays)}
                                            onChange={(val) => setDurationDays(Number(val))}
                                            color="blue"
                                            className="!text-blue-gray-900 dark:!text-white !bg-white dark:!bg-gray-800 !border-blue-gray-200 focus:!border-blue-500"
                                            labelProps={{
                                                className: "!text-blue-gray-500 dark:!text-gray-400",
                                            }}
                                            disabled={loading}
                                            menuProps={{
                                                className: "p-2 bg-white dark:bg-gray-900 border border-blue-gray-50 dark:border-gray-800 shadow-lg shadow-blue-gray-500/10 dark:shadow-black/50 rounded-xl min-w-[200px] max-h-[300px] overflow-y-auto z-[9999]",
                                                animate: {
                                                    mount: { y: 0, scale: 1, opacity: 1 },
                                                    unmount: { y: 10, scale: 0.95, opacity: 0 },
                                                },
                                            }}
                                        >
                                            {durationOptions.map((opt) => (
                                                <Option key={opt.value} value={String(opt.value)} className="mb-1 rounded-lg py-2.5 px-3 text-sm font-medium transition-all hover:bg-blue-50 hover:text-blue-700 focus:bg-blue-50 focus:text-blue-700 dark:text-gray-300 dark:hover:bg-gray-800 dark:hover:text-blue-300 dark:focus:bg-gray-800">
                                                    {opt.label} - {opt.sub}
                                                </Option>
                                            ))}
                                        </Select>
                                    </div>


                                    {error && (
                                        <Alert color="red" icon={<ExclamationTriangleIcon className="h-5 w-5" />}>
                                            {error}
                                        </Alert>
                                    )}

                                    <div className="mt-2 text-left">
                                        <Button
                                            type="submit"
                                            disabled={loading || (activeTab === 'syllabus' && !selectedFile)}
                                            fullWidth
                                            className={`w-full md:w-auto px-8 py-3 rounded-xl shadow-lg transition-all duration-300 active:scale-95 hover:scale-[1.02] flex items-center justify-center gap-2 ${activeTab === 'syllabus' && !selectedFile
                                                ? 'bg-gray-300 text-gray-500 shadow-none cursor-not-allowed'
                                                : 'bg-gradient-to-r from-blue-600 to-blue-500 shadow-blue-500/20 hover:shadow-blue-500/40'
                                                }`}
                                        >
                                            <SparklesIcon className="h-4 w-4" />
                                            {activeTab === 'topic' ? "GENERATE PLAN" : "ANALYZE & GENERATE"}
                                        </Button>
                                    </div>
                                </form>
                            </div>
                        )}
                    </CardBody>
                </Card>

                {/* History Section - Moved to Bottom */}
                <div className="space-y-6">
                    <div>
                        <Typography variant="h5" color="blue-gray" className="mb-1 dark:text-white">
                            Your Plans
                        </Typography>
                        <Typography variant="small" className="font-normal text-blue-gray-600 dark:text-gray-400">
                            Continue where you left off
                        </Typography>
                    </div>

                    {historyLoading ? (
                        <div className="flex justify-center py-12">
                            <Spinner className="h-8 w-8 text-blue-gray-900 dark:text-white" />
                        </div>
                    ) : history.length === 0 ? (
                        <Card className="border border-blue-gray-100 dark:border-gray-700 shadow-sm bg-white/80 dark:bg-gray-900/80 backdrop-blur-md">
                            <CardBody className="text-center py-12">
                                <BookOpenIcon className="h-12 w-12 text-blue-gray-200 dark:text-gray-700 mx-auto mb-4" />
                                <Typography color="blue-gray" variant="h6" className="dark:text-white">
                                    No plans yet
                                </Typography>
                                <Typography color="gray" className="font-normal mt-1 dark:text-gray-400">
                                    Create your first study plan to get started!
                                </Typography>
                            </CardBody>
                        </Card>
                    ) : (
                        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                            {history.map((plan) => (
                                <Card
                                    key={plan.id}
                                    className="cursor-pointer border border-blue-gray-100 dark:border-gray-700 hover:shadow-md transition-all group bg-white/80 dark:bg-gray-900/80 backdrop-blur-md"
                                    onClick={() => navigate(`/dashboard/study-plan/${plan.id}`)}
                                >
                                    <CardBody className="p-5 relative">
                                        <div className="absolute top-4 right-4 z-10">
                                            <IconButton
                                                variant="text"
                                                color="red"
                                                size="sm"
                                                className="hover:bg-red-50 dark:hover:bg-red-900/20 opacity-0 group-hover:opacity-100 transition-opacity"
                                                onClick={(e) => handleDelete(plan.id, e)}
                                            >
                                                <TrashIcon className="h-5 w-5" />
                                            </IconButton>
                                        </div>

                                        <div className="flex justify-between items-start mb-3">
                                            <Chip
                                                size="sm"
                                                variant="ghost"
                                                value={plan.difficulty}
                                                className={`rounded-full ${plan.difficulty === 'Advanced'
                                                    ? 'bg-red-50 text-red-700 dark:bg-red-900/20 dark:text-red-300'
                                                    : plan.difficulty === 'Intermediate'
                                                        ? 'bg-amber-50 text-amber-700 dark:bg-amber-900/20 dark:text-amber-300'
                                                        : 'bg-green-50 text-green-700 dark:bg-green-900/20 dark:text-green-300'
                                                    }`}
                                            />
                                            {plan.completed && (
                                                <Chip
                                                    size="sm"
                                                    value="Completed"
                                                    icon={<CheckCircleIcon />}
                                                    className="rounded-full pl-2 bg-green-50 text-green-700 dark:bg-green-900/20 dark:text-green-300"
                                                    variant="ghost"
                                                />
                                            )}
                                        </div>

                                        <Typography variant="h6" color="blue-gray" className="mb-1 group-hover:text-blue-500 transition-colors dark:text-white line-clamp-1 pr-8">
                                            {plan.title}
                                        </Typography>

                                        <Typography className="font-normal text-gray-600 dark:text-gray-400 line-clamp-2 text-sm mb-4">
                                            {plan.description}
                                        </Typography>

                                        <div className="pt-4 border-t border-blue-gray-50 dark:border-gray-800">
                                            <div className="flex items-center justify-between mb-2">
                                                <Typography variant="small" className="font-medium text-blue-gray-600 dark:text-gray-300">
                                                    {plan.progress}% Complete
                                                </Typography>
                                                <Typography variant="small" className="text-gray-500 dark:text-gray-500">
                                                    {formatDate(plan.createdAt)}
                                                </Typography>
                                            </div>
                                            <div className="w-full bg-blue-gray-50 dark:bg-gray-800 rounded-full h-1.5">
                                                <div
                                                    className="h-1.5 rounded-full bg-blue-500 transition-all duration-500"
                                                    style={{ width: `${plan.progress}%` }}
                                                />
                                            </div>
                                        </div>
                                    </CardBody>
                                </Card>
                            ))}
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};

export { StudyPlanBuilderPage };
export default StudyPlanBuilderPage;
